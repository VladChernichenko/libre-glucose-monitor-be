package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.IsfMealWindowSnapshot;
import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.IsfMealWindowDTO;
import che.glucosemonitorbe.dto.IsfMealWindowProfileResponse;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.IsfMealWindowSnapshotRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes observational per-meal-window ISF estimates from each user's historical bolus
 * events + CGM trace. Snapshots are cached in {@code isf_meal_window_snapshots} and refreshed
 * by a daily scheduler and on every new bolus.
 *
 * <h3>Algorithm (per bolus B at time t_B)</h3>
 * <ol>
 *   <li>Define the analysis window {@code [t_B, t_B + DIA]} (user's rapid-acting DIA, typ. 4.5 h).</li>
 *   <li>Read CGM at t_B (baseline) and at t_B + DIA (post). Skip if either is missing.</li>
 *   <li>Compute expected glucose rise from any carbs logged in [-30 min, +DIA] using the user's
 *       carb ratio: {@code Δcarb = (gramsAbsorbed / 10) × CR}.</li>
 *   <li>Residual drop attributable to insulin: {@code Δinsulin = Δcarb − (postCGM − preCGM)}.</li>
 *   <li>Per-event ISF estimate: {@code ISF = Δinsulin / B.units}.</li>
 *   <li>Weight: 1.0 if no carbs nearby (correction bolus), else 0.4 (deconvolved meal bolus).</li>
 *   <li>Bucket by {@link MealWindow#fromTimestamp(LocalDateTime)} — night events are dropped.</li>
 * </ol>
 *
 * <p>Per-meal-window result is the weighted mean of all per-event ISF estimates in that bucket.
 * Buckets with weighted-sample-sum &lt; {@link #MIN_WEIGHTED_SAMPLES} return {@code null} so
 * the front-end can render a gap with a "Run ISF experiment" CTA.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IsfMealWindowProfileService {

    /** Lookback window for observational data. */
    public static final int HISTORY_DAYS = 14;

    /** A bucket must accumulate at least this much weighted sample mass to expose an ISF value. */
    public static final double MIN_WEIGHTED_SAMPLES = 7.0;

    /** Weight applied to a correction bolus (no significant carbs nearby). */
    public static final double WEIGHT_CORRECTION = 1.0;

    /** Weight applied to a meal-attached bolus (lower trust — depends on user's CR). */
    public static final double WEIGHT_MEAL = 0.4;

    /** A bolus is treated as "correction" when nearby carbs (±2 h around t_B) sum below this. */
    public static final double CARB_THRESHOLD_GRAMS = 10.0;

    /** Plausibility band — per-event ISF estimates outside this range are dropped as noise. */
    public static final double MIN_PLAUSIBLE_ISF = 0.3;
    public static final double MAX_PLAUSIBLE_ISF = 10.0;

    /** CGM lookup tolerance (minutes) — pick the nearest CGM reading within this window. */
    private static final int CGM_LOOKUP_WINDOW_MIN = 15;

    /** Default carb ratio fallback when user has none configured (mmol/L per 10 g). */
    private static final double DEFAULT_CARB_RATIO = 2.0;

    private final NoteRepository noteRepository;
    private final CgmReadingRepository cgmReadingRepository;
    private final UserInsulinPreferencesService userInsulinPreferencesService;
    private final COBSettingsService cobSettingsService;
    private final InsulinCalculatorService insulinCalculatorService;
    private final CarbsOnBoardService carbsOnBoardService;
    private final IsfMealWindowSnapshotRepository snapshotRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the cached profile for the user. Always returns 3 entries
     * (BREAKFAST, LUNCH, DINNER) — missing buckets surface as {@code hasData=false}.
     */
    @Transactional(readOnly = true)
    public IsfMealWindowProfileResponse getProfile(UUID userId) {
        Map<MealWindow, IsfMealWindowSnapshot> byWindow = new EnumMap<>(MealWindow.class);
        snapshotRepository.findByUserId(userId)
                .forEach(s -> byWindow.put(s.getMealWindow(), s));

        List<IsfMealWindowDTO> dtos = new ArrayList<>(MealWindow.values().length);
        for (MealWindow window : MealWindow.values()) {
            IsfMealWindowSnapshot snap = byWindow.get(window);
            dtos.add(toDto(window, snap));
        }
        return IsfMealWindowProfileResponse.builder()
                .windows(dtos)
                .minWeightedSamples(MIN_WEIGHTED_SAMPLES)
                .historyDays(HISTORY_DAYS)
                .build();
    }

    /**
     * Recomputes all three meal-window snapshots from scratch for the user and persists them.
     * Idempotent — safe to call from both the daily scheduler and an on-bolus hook.
     */
    @Transactional
    public IsfMealWindowProfileResponse recomputeForUser(UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(HISTORY_DAYS);

        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(userId, since, now);
        List<CgmReading> cgmReadings = loadCgmReadings(userId, since);

        RapidInsulinIobParameters rapid = userInsulinPreferencesService.getRapidIobParameters(userId);
        COBSettingsDTO cobSettings = cobSettingsService.getCOBSettings(userId);
        double carbRatio = (cobSettings != null && cobSettings.getCarbRatio() != null && cobSettings.getCarbRatio() > 0)
                ? cobSettings.getCarbRatio()
                : DEFAULT_CARB_RATIO;

        List<EventEstimate> estimates = new ArrayList<>();
        for (Note bolus : notes) {
            if (bolus.getInsulin() == null || bolus.getInsulin() <= 0) continue;
            if (bolus.isLongActing()) continue;
            if (bolus.getTimestamp() == null) continue;

            Optional<MealWindow> windowOpt = MealWindow.fromTimestamp(bolus.getTimestamp());
            if (windowOpt.isEmpty()) continue; // night — skip

            EventEstimate est = estimateForBolus(bolus, notes, cgmReadings, rapid, cobSettings, carbRatio);
            if (est != null) {
                estimates.add(est);
            }
        }

        Map<MealWindow, BucketAccumulator> byWindow = new EnumMap<>(MealWindow.class);
        for (MealWindow w : MealWindow.values()) byWindow.put(w, new BucketAccumulator());

        for (EventEstimate est : estimates) {
            byWindow.get(est.window).add(est.isf, est.weight);
        }

        for (MealWindow window : MealWindow.values()) {
            BucketAccumulator acc = byWindow.get(window);
            IsfMealWindowSnapshot snap = snapshotRepository.findByUserIdAndMealWindow(userId, window)
                    .orElseGet(() -> IsfMealWindowSnapshot.builder()
                            .userId(userId)
                            .mealWindow(window)
                            .build());
            snap.setWeightedSamples(acc.totalWeight);
            snap.setRawSampleCount(acc.rawCount);
            snap.setIsfMmolPerU(acc.totalWeight >= MIN_WEIGHTED_SAMPLES ? acc.weightedMean() : null);
            snapshotRepository.save(snap);
        }

        log.info("ISF profile recomputed user={} estimates={} breakfast={} lunch={} dinner={}",
                userId, estimates.size(),
                byWindow.get(MealWindow.BREAKFAST).summary(),
                byWindow.get(MealWindow.LUNCH).summary(),
                byWindow.get(MealWindow.DINNER).summary());

        return getProfile(userId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────────

    private List<CgmReading> loadCgmReadings(UUID userId, LocalDateTime since) {
        long sinceEpochMs = since.toInstant(ZoneOffset.UTC).toEpochMilli();
        return cgmReadingRepository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(
                userId, sinceEpochMs);
    }

    /**
     * Produces an ISF estimate for one bolus, or {@code null} when the event is unusable
     * (missing CGM coverage, IOB stacking, implausible result).
     */
    EventEstimate estimateForBolus(
            Note bolus,
            List<Note> allNotes,
            List<CgmReading> cgmReadings,
            RapidInsulinIobParameters rapid,
            COBSettingsDTO cobSettings,
            double carbRatio) {

        LocalDateTime t0 = bolus.getTimestamp();
        LocalDateTime tEnd = t0.plusMinutes((long) (rapid.diaHours() * 60));

        Double preCgm = nearestCgmMmol(cgmReadings, t0);
        Double postCgm = nearestCgmMmol(cgmReadings, tEnd);
        if (preCgm == null || postCgm == null) {
            return null;
        }

        // Reject if another bolus stacks within the window — confounds attribution.
        for (Note other : allNotes) {
            if (other == bolus) continue;
            if (other.isLongActing()) continue;
            if (other.getInsulin() == null || other.getInsulin() <= 0) continue;
            if (other.getTimestamp() == null) continue;
            if (!other.getTimestamp().isAfter(t0)) continue;
            if (other.getTimestamp().isBefore(tEnd)) {
                return null;
            }
        }

        // Carbs in [-30 min, +DIA] are considered part of this event's nutrient envelope.
        LocalDateTime carbWindowStart = t0.minusMinutes(30);
        double carbsGrams = 0.0;
        List<che.glucosemonitorbe.domain.CarbsEntry> carbEntries = new ArrayList<>();
        for (Note n : allNotes) {
            if (n.getCarbs() == null || n.getCarbs() <= 0) continue;
            if (n.getTimestamp() == null) continue;
            if (n.getTimestamp().isBefore(carbWindowStart)) continue;
            if (n.getTimestamp().isAfter(tEnd)) continue;
            carbsGrams += n.getCarbs();
            carbEntries.add(buildCarbsEntry(n));
        }

        // Carbs that remained un-absorbed at tEnd don't contribute to the observed Δglucose.
        double cobAtEnd = carbsOnBoardService.calculateTotalCarbsOnBoard(carbEntries, tEnd, cobSettings);
        double absorbedGrams = Math.max(0.0, carbsGrams - cobAtEnd);
        double deltaCarbMmol = (absorbedGrams / 10.0) * carbRatio;
        double observedDelta = postCgm - preCgm; // mmol/L change from t0 to tEnd
        double insulinAttributedDrop = deltaCarbMmol - observedDelta;

        double units = bolus.getInsulin();
        double isf = insulinAttributedDrop / units;

        if (Double.isNaN(isf) || Double.isInfinite(isf)) return null;
        if (isf < MIN_PLAUSIBLE_ISF || isf > MAX_PLAUSIBLE_ISF) return null;

        double weight = (carbsGrams < CARB_THRESHOLD_GRAMS) ? WEIGHT_CORRECTION : WEIGHT_MEAL;
        MealWindow window = MealWindow.fromTimestamp(t0).orElse(null);
        if (window == null) return null;

        return new EventEstimate(window, isf, weight);
    }

    /**
     * Finds the CGM reading closest to {@code target} within {@link #CGM_LOOKUP_WINDOW_MIN}
     * minutes. Returns {@code null} if none found in range. mmol/L conversion: backend stores
     * {@code sgv} as mg/dL × 1 (or as mmol/L × 10 depending on source) — this helper assumes
     * mg/dL, the dominant Nightscout convention. LibreLinkUp readings already arrive normalised.
     */
    Double nearestCgmMmol(List<CgmReading> readings, LocalDateTime target) {
        if (readings == null || readings.isEmpty() || target == null) return null;
        long targetEpochMs = target.toInstant(ZoneOffset.UTC).toEpochMilli();
        long bestDelta = Long.MAX_VALUE;
        CgmReading best = null;
        for (CgmReading r : readings) {
            if (r.getDateTimestamp() == null || r.getSgv() == null) continue;
            long delta = Math.abs(r.getDateTimestamp() - targetEpochMs);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = r;
            }
        }
        if (best == null) return null;
        if (bestDelta > CGM_LOOKUP_WINDOW_MIN * 60L * 1000L) return null;
        return sgvToMmol(best.getSgv());
    }

    /** Nightscout stores sgv as mg/dL — divide by 18.0182 for mmol/L. */
    static double sgvToMmol(int sgv) {
        return sgv / 18.0182;
    }

    private che.glucosemonitorbe.domain.CarbsEntry buildCarbsEntry(Note n) {
        che.glucosemonitorbe.domain.CarbsEntry e = che.glucosemonitorbe.domain.CarbsEntry.builder()
                .id(n.getId())
                .timestamp(n.getTimestamp())
                .carbs(n.getCarbs())
                .insulin(n.getInsulin() != null ? n.getInsulin() : 0.0)
                .mealType(n.getMeal())
                .originalCarbs(n.getCarbs())
                .userId(n.getUserId())
                .build();
        e.setAbsorptionMode(n.getAbsorptionMode() != null ? n.getAbsorptionMode() : "DEFAULT_DECAY");
        return e;
    }

    private IsfMealWindowDTO toDto(MealWindow window, IsfMealWindowSnapshot snap) {
        if (snap == null) {
            return IsfMealWindowDTO.builder()
                    .mealWindow(window.name())
                    .startHour(window.getStartHourInclusive())
                    .endHour(window.getEndHourExclusive())
                    .isfMmolPerU(null)
                    .weightedSamples(0.0)
                    .rawSampleCount(0)
                    .hasData(false)
                    .lastUpdated(null)
                    .build();
        }
        boolean hasData = snap.getIsfMmolPerU() != null
                && snap.getWeightedSamples() != null
                && snap.getWeightedSamples() >= MIN_WEIGHTED_SAMPLES;
        return IsfMealWindowDTO.builder()
                .mealWindow(window.name())
                .startHour(window.getStartHourInclusive())
                .endHour(window.getEndHourExclusive())
                .isfMmolPerU(hasData ? snap.getIsfMmolPerU() : null)
                .weightedSamples(snap.getWeightedSamples())
                .rawSampleCount(snap.getRawSampleCount())
                .hasData(hasData)
                .lastUpdated(snap.getLastUpdated())
                .build();
    }

    // Test-only access to internal constants for assertions
    static List<MealWindow> orderedWindows() {
        return Arrays.asList(MealWindow.values());
    }

    /** Per-event ISF estimate prior to bucketing. */
    static final class EventEstimate {
        final MealWindow window;
        final double isf;
        final double weight;

        EventEstimate(MealWindow window, double isf, double weight) {
            this.window = window;
            this.isf = isf;
            this.weight = weight;
        }
    }

    /** Accumulates weighted-mean ISF + raw counts for one meal-window bucket. */
    static final class BucketAccumulator {
        double totalWeight = 0.0;
        double weightedSum = 0.0;
        int rawCount = 0;

        void add(double isf, double weight) {
            totalWeight += weight;
            weightedSum += isf * weight;
            rawCount += 1;
        }

        double weightedMean() {
            return totalWeight > 0 ? weightedSum / totalWeight : Double.NaN;
        }

        String summary() {
            return String.format("[isf=%.2f wSamples=%.2f n=%d]",
                    totalWeight > 0 ? weightedMean() : Double.NaN, totalWeight, rawCount);
        }
    }
}
