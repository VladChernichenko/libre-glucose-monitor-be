package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.IsfMealWindowSnapshot;
import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.IsfMealWindowDTO;
import che.glucosemonitorbe.dto.IsfMealWindowProfileResponse;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.IsfMealWindowSnapshotRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Computes observational per-meal-window ISF estimates from each user's historical bolus
 * events + CGM trace. Snapshots are cached in {@code isf_meal_window_snapshots} and refreshed
 * by a daily scheduler and on every new bolus.
 *
 * <h3>Algorithm (per bolus B at time t_B)</h3>
 * <ol>
 *   <li>Define the analysis window {@code [t_B, t_B + DIA]} (user's rapid-acting DIA, typ. 4.5 h).</li>
 *   <li>Read CGM at t_B (baseline) and at t_B + DIA (post). Skip if either is missing.</li>
 *   <li>Estimate ISF from the observed change and the insulin-to-carb ratio (grams per unit):
 *       {@code ISF = observedDelta / (absorbedGrams / ICR - units)}, where {@code units} includes the
 *       insulin still on board from earlier boluses and {@code absorbedGrams} the carbs still on
 *       board from earlier meals. No estimate when the denominator is under
 *       {@link #MIN_NET_UNITS} or the ICR is missing.</li>
 *   <li>Weight: 1.0 if no carbs nearby (correction bolus), else 0.4 (deconvolved meal bolus).</li>
 *   <li>Bucket by {@link MealWindow#fromTimestamp(LocalDateTime)} (full 24h: B/L/D/NIGHT).</li>
 * </ol>
 *
 * <p>Per-meal-window result is the weighted mean of all per-event ISF estimates in that bucket.
 * Buckets with weighted-sample-sum &lt; {@link #MIN_WEIGHTED_SAMPLES} return {@code null} so
 * the front-end can render a gap.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IsfMealWindowProfileService {

    /** Lookback window for observational data. */
    public static final int HISTORY_DAYS = 14;

    /**
     * A bucket must accumulate at least this much weighted sample mass to expose an ISF value.
     * Real 14-day traces often yield only a few clean (unstacked) corrections per window after
     * plausibility filters, so a single trusted event is enough to expose a value.
     */
    public static final double MIN_WEIGHTED_SAMPLES = 1.0;

    /** Weight applied to a correction bolus (no significant carbs nearby). */
    public static final double WEIGHT_CORRECTION = 1.0;

    /** Weight applied to a meal-attached bolus (lower trust - depends on user's CR). */
    public static final double WEIGHT_MEAL = 0.4;

    /** A bolus is treated as "correction" when nearby carbs (±2 h around t_B) sum below this. */
    public static final double CARB_THRESHOLD_GRAMS = 10.0;

    /** Plausibility band - per-event ISF estimates outside this range are dropped as noise. */
    public static final double MIN_PLAUSIBLE_ISF = 0.3;
    public static final double MAX_PLAUSIBLE_ISF = 10.0;

    /** CGM lookup tolerance (minutes) - pick the nearest CGM reading within this window. */
    private static final int CGM_LOOKUP_WINDOW_MIN = 15;

    /**
     * Smallest net insulin [U] an estimate may divide by. {@code ISF = Δ / (absorbed/ICR − units)},
     * so when a bolus matched its meal the denominator is near zero and ordinary CGM noise becomes
     * an ISF of any size and either sign. Half a unit keeps a 0.5 mmol/L reading error under
     * 1 mmol/L per unit of ISF error.
     */
    public static final double MIN_NET_UNITS = 0.5;

    /** How far back earlier meals are searched for carbs still on board at the bolus [hours]. */
    private static final int PRIOR_CARB_LOOKBACK_HOURS = 8;

    /**
     * Drop a bolus when another rapid dose lands inside this many minutes after it. Using the full
     * DIA here rejected almost every daytime event in real logs (meal + correction pairs); 90 min
     * still drops true double-boluses while letting spaced corrections contribute to other windows.
     * Earlier IOB is still subtracted via {@link InsulinCalculatorService#iobOpenApsExponential}.
     */
    public static final int STACK_REJECT_MINUTES = 90;

    /** Same envelope as {@link InsulinCalculatorService}: an ICR outside it is not an estimate. */
    private static final double MIN_GRAMS_PER_UNIT = 3.0;
    private static final double MAX_GRAMS_PER_UNIT = 30.0;

    private final NoteRepository noteRepository;
    private final CgmReadingRepository cgmReadingRepository;
    private final UserInsulinPreferencesService userInsulinPreferencesService;
    private final UserSettingsService userSettingsService;
    private final CarbsOnBoardService carbsOnBoardService;
    private final IsfMealWindowSnapshotRepository snapshotRepository;

    // ---
    // Public API
    // ---

    /**
     * Returns the cached profile for the user. Always returns 4 entries
     * (BREAKFAST, LUNCH, DINNER, NIGHT) - missing buckets surface as {@code hasData=false}.
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
     * Recomputes all meal-window snapshots from scratch for the user and persists them.
     * Idempotent - safe to call from both the daily scheduler and an on-bolus hook.
     */
    @Transactional
    public IsfMealWindowProfileResponse recomputeForUser(UUID userId) {
        RapidInsulinIobParameters rapid = userInsulinPreferencesService.getRapidIobParameters(userId);
        UserSettingsDTO userSettings = userSettingsService.getUserSettings(userId);

        // Meal-window labels use the user's IANA zone. Note↔CGM matching does not: see
        // {@link #noteTimestampToEpochMs}. The notes query bounds use the JVM zone so they share
        // the same LocalDateTime axis Hibernate uses for timestamptz ↔ LocalDateTime.
        ZoneId userZone = userSettings == null
                ? ZoneOffset.UTC
                : UserZones.resolve(userSettings.getTimezone(), userSettings.getUtcOffsetMinutes());
        ZoneId noteZone = ZoneId.systemDefault();

        LocalDateTime now = LocalDateTime.now(noteZone);
        LocalDateTime since = now.minusDays(HISTORY_DAYS);

        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(userId, since, now);
        List<CgmReading> cgmReadings = loadCgmReadings(userId, since);

        Double gramsPerUnit = userSettings == null ? null : userSettings.getGramsPerUnit();

        List<EventEstimate> estimates = new ArrayList<>();
        int candidates = 0;
        int rejectedNoCgm = 0;
        int rejectedStacked = 0;
        int rejectedOther = 0;
        for (Note bolus : notes) {
            if (bolus.getInsulin() == null || bolus.getInsulin() <= 0) continue;
            if (bolus.isLongActing()) continue;
            if (bolus.getTimestamp() == null) continue;
            candidates++;

            EventEstimate est = estimateForBolus(bolus, notes, cgmReadings, rapid, userSettings, gramsPerUnit, userZone);
            if (est != null) {
                estimates.add(est);
            } else {
                // Cheap reject attribution for ops logs (mirrors estimateForBolus gates).
                LocalDateTime t0 = bolus.getTimestamp();
                LocalDateTime tEnd = t0.plusMinutes((long) (rapid.diaHours() * 60));
                boolean noCgm = nearestCgmMmol(cgmReadings, t0) == null
                        || nearestCgmMmol(cgmReadings, tEnd) == null;
                boolean stacked = false;
                if (!noCgm) {
                    LocalDateTime stackDeadline = t0.plusMinutes(STACK_REJECT_MINUTES);
                    for (Note other : notes) {
                        if (other == bolus || other.isLongActing()) continue;
                        if (other.getInsulin() == null || other.getInsulin() <= 0) continue;
                        if (other.getTimestamp() == null || !other.getTimestamp().isAfter(t0)) continue;
                        if (other.getTimestamp().isBefore(stackDeadline)) {
                            stacked = true;
                            break;
                        }
                    }
                }
                if (noCgm) rejectedNoCgm++;
                else if (stacked) rejectedStacked++;
                else rejectedOther++;
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

        log.info("ISF profile recomputed user={} candidates={} estimates={} rejectedNoCgm={} "
                        + "rejectedStacked={} rejectedOther={} breakfast={} lunch={} dinner={} night={}",
                userId, candidates, estimates.size(), rejectedNoCgm, rejectedStacked, rejectedOther,
                byWindow.get(MealWindow.BREAKFAST).summary(),
                byWindow.get(MealWindow.LUNCH).summary(),
                byWindow.get(MealWindow.DINNER).summary(),
                byWindow.get(MealWindow.NIGHT).summary());

        return getProfile(userId);
    }

    // ---
    // Internals
    // ---

    /**
     * @param since lower bound of the history window on the JVM/note LocalDateTime axis (see
     *              {@link #recomputeForUser}); converted to the UTC epochs that
     *              {@code cgm_readings.date_timestamp} stores via {@link #noteTimestampToEpochMs}.
     */
    private List<CgmReading> loadCgmReadings(UUID userId, LocalDateTime since) {
        long sinceEpochMs = noteTimestampToEpochMs(since);
        return cgmReadingRepository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(
                userId, sinceEpochMs);
    }

    /**
     * Epoch-millis for a {@code notes.timestamp} LocalDateTime.
     *
     * <p>{@code notes.timestamp} is a {@code timestamptz}. Hibernate exposes it as
     * {@link LocalDateTime} in the <em>JVM</em> zone — the wall-clock digits of the stored
     * Instant on this host — not necessarily {@code user_settings.timezone}. CGM rows store true
     * UTC epochs. Converting those digits with the user zone when JVM ≠ user zone (e.g. JVM
     * {@code Asia/Tbilisi} vs settings {@code Europe/Moscow}) skews every probe by the offset
     * delta and drops nearly all ISF estimates inside the 15-minute CGM window.
     */
    static long noteTimestampToEpochMs(LocalDateTime noteLocal) {
        if (noteLocal == null) {
            return Instant.EPOCH.toEpochMilli();
        }
        return noteLocal.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
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
            UserSettingsDTO userSettings,
            Double gramsPerUnit,
            ZoneId userZone) {

        LocalDateTime t0 = bolus.getTimestamp();
        LocalDateTime tEnd = t0.plusMinutes((long) (rapid.diaHours() * 60));

        Double preCgm = nearestCgmMmol(cgmReadings, t0);
        Double postCgm = nearestCgmMmol(cgmReadings, tEnd);
        if (preCgm == null || postCgm == null) {
            return null;
        }

        // Reject if another bolus stacks soon after - confounds the short attribution window.
        LocalDateTime stackDeadline = t0.plusMinutes(STACK_REJECT_MINUTES);
        for (Note other : allNotes) {
            if (other == bolus) continue;
            if (other.isLongActing()) continue;
            if (other.getInsulin() == null || other.getInsulin() <= 0) continue;
            if (other.getTimestamp() == null) continue;
            if (!other.getTimestamp().isAfter(t0)) continue;
            if (other.getTimestamp().isBefore(stackDeadline)) {
                return null;
            }
        }

        // Insulin still on board from earlier rapid boluses acts inside this window as well - all of
        // it, since a dose given before t0 is spent before t0 + DIA. Leaving it out credits its
        // whole effect to this bolus: 4 U at 12:00 and a 1 U correction at 12:30 would read as an
        // ISF five times too large.
        double priorIob = 0.0;
        for (Note other : allNotes) {
            if (other == bolus || other.isLongActing()) continue;
            if (other.getInsulin() == null || other.getInsulin() <= 0) continue;
            if (other.getTimestamp() == null || other.getTimestamp().isAfter(t0)) continue;
            double minsAgo = java.time.Duration.between(other.getTimestamp(), t0).toMinutes();
            priorIob += InsulinCalculatorService.iobOpenApsExponential(
                    other.getInsulin(), minsAgo, rapid.diaHours(), rapid.peakMinutes());
        }

        // Carbs in [-30 min, +DIA] are considered part of this event's nutrient envelope. Earlier
        // meals count only for what they still have on board at t0: that share is absorbed inside
        // the window too, and what was absorbed before it is already in the baseline reading.
        LocalDateTime carbWindowStart = t0.minusMinutes(30);
        LocalDateTime priorCarbStart = t0.minusHours(PRIOR_CARB_LOOKBACK_HOURS);
        double carbsGrams = 0.0;
        List<che.glucosemonitorbe.domain.CarbsEntry> carbEntries = new ArrayList<>();
        List<che.glucosemonitorbe.domain.CarbsEntry> priorCarbEntries = new ArrayList<>();
        for (Note n : allNotes) {
            // A rescue carb is a hypo treatment, not part of this bolus's nutrient envelope.
            // Counting it would push carbsGrams past CARB_THRESHOLD_GRAMS - reclassifying a
            // correction bolus as meal-attached - and subtract a meal rise that never happened.
            if (n.isHypoTreatment()) continue;
            if (n.getCarbs() == null || n.getCarbs() <= 0) continue;
            if (n.getTimestamp() == null) continue;
            if (n.getTimestamp().isAfter(tEnd)) continue;
            if (n.getTimestamp().isBefore(carbWindowStart)) {
                if (!n.getTimestamp().isBefore(priorCarbStart)) priorCarbEntries.add(buildCarbsEntry(n));
                continue;
            }
            carbsGrams += n.getCarbs();
            carbEntries.add(buildCarbsEntry(n));
        }
        double priorCobAtStart = priorCarbEntries.isEmpty() ? 0.0
                : carbsOnBoardService.calculateTotalCarbsOnBoard(priorCarbEntries, t0, userSettings);
        carbEntries.addAll(priorCarbEntries);

        // Carbs that remained un-absorbed at tEnd don't contribute to the observed Δglucose.
        double cobAtEnd = carbsOnBoardService.calculateTotalCarbsOnBoard(carbEntries, tEnd, userSettings);
        double absorbedGrams = Math.max(0.0, carbsGrams + priorCobAtStart - cobAtEnd);
        if (gramsPerUnit == null || !Double.isFinite(gramsPerUnit)
                || gramsPerUnit < MIN_GRAMS_PER_UNIT || gramsPerUnit > MAX_GRAMS_PER_UNIT) {
            return null;
        }

        double units = bolus.getInsulin() + priorIob;
        double observedDelta = postCgm - preCgm; // mmol/L change from t0 to tEnd
        double denominator = absorbedGrams / gramsPerUnit - units;
        if (Math.abs(denominator) < MIN_NET_UNITS) {
            return null;
        }
        double isf = observedDelta / denominator;

        if (Double.isNaN(isf) || Double.isInfinite(isf)) return null;
        if (isf < MIN_PLAUSIBLE_ISF || isf > MAX_PLAUSIBLE_ISF) return null;

        double weight = (carbsGrams + priorCobAtStart < CARB_THRESHOLD_GRAMS) ? WEIGHT_CORRECTION : WEIGHT_MEAL;
        // Bucket on the note LocalDateTime hour (device/JVM wall of the timestamptz Instant).
        // Remapping through user_settings.timezone when it disagrees with the device zone (e.g.
        // settings Europe/Moscow vs notes written in Asia/Tbilisi) shifts boundary hours into the
        // wrong meal window and collapses sparse daytime samples into NIGHT.
        MealWindow window = MealWindow.fromTimestamp(t0).orElse(null);
        if (window == null) return null;

        return new EventEstimate(window, isf, weight);
    }

    /**
     * Finds the CGM reading closest to {@code target} within {@link #CGM_LOOKUP_WINDOW_MIN}
     * minutes. Returns {@code null} if none found in range. mmol/L conversion: backend stores
     * {@code sgv} as mg/dL × 1 (or as mmol/L × 10 depending on source) - this helper assumes
     * mg/dL, the dominant Nightscout convention. LibreLinkUp readings already arrive normalised.
     *
     * @param target a bolus time (or bolus + DIA) from {@code notes.timestamp} — see
     *               {@link #noteTimestampToEpochMs}
     */
    Double nearestCgmMmol(List<CgmReading> readings, LocalDateTime target) {
        if (readings == null || readings.isEmpty() || target == null) return null;
        long targetEpochMs = noteTimestampToEpochMs(target);
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

    /** Nightscout stores sgv as mg/dL - divide by 18.0182 for mmol/L. */
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
