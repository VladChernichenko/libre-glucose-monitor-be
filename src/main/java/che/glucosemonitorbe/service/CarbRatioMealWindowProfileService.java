package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbRatioMealWindowSnapshot;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.dto.CarbRatioWindowEstimate;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CarbRatioMealWindowSnapshotRepository;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Learns the insulin-to-carb ratio (grams per unit) of each meal window from the clean meals of the
 * last {@link #HISTORY_DAYS} days and caches it in {@code carb_ratio_meal_window_snapshots}.
 * Recomputed by {@code IsfMealWindowScheduler} and the manual recompute endpoint; a learned value is
 * never written to {@code user_settings} here.
 *
 * <h3>Per meal</h3>
 * <ol>
 *   <li><b>Anchor:</b> a meal is a note with at least {@link #MEAL_CARB_THRESHOLD_GRAMS} of non-rescue
 *       carbs. Its rapid boluses are those within {@link #ANCHOR_TOLERANCE_MINUTES} of it; the earliest
 *       is {@code t0} (a split bolus or a separately logged pre-bolus is one event). No bolus: no event.</li>
 *   <li><b>Clean:</b> skipped when another meal lands in {@code (t0 + 30 min, t0 + DIA]}, when CGM is
 *       missing at {@code t0} or {@code t0 + DIA}, or when {@code t0} is excluded from learning by an
 *       illness span.</li>
 *   <li><b>Carbs:</b> every carb entry in {@code [t0 − 30 min, t0 + DIA]}, rescue carbs included, plus
 *       carbs still on board at {@code t0} from the previous {@link #PRIOR_CARB_LOOKBACK_HOURS} h, minus
 *       carbs still on board at {@code t0 + DIA}.</li>
 *   <li><b>Insulin:</b> insulin on board at {@code t0} from earlier doses, the doses at {@code t0}, and
 *       the share of every later dose in the window that acted by {@code t0 + DIA} - each on its own
 *       insulin's curve.</li>
 *   <li><b>Estimate:</b> {@code unitsNeeded = insulin + Δglucose / windowIsf};
 *       {@code ratio = carbs / unitsNeeded}, kept when {@code unitsNeeded ≥ 0.5} and the ratio is 3-30.</li>
 * </ol>
 *
 * <p>Per window: the median of the estimates once there are {@link #MIN_MEALS}, otherwise no value.
 * Meal windows follow the note's own hour, and note↔CGM matching uses the JVM zone, exactly as
 * {@link IsfMealWindowProfileService}.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CarbRatioMealWindowProfileService {

    /** Lookback window for observational data. */
    public static final int HISTORY_DAYS = 14;
    /** Clean meals a window needs before it exposes a ratio. */
    public static final int MIN_MEALS = 3;
    /** Non-rescue carbs a note needs to count as a meal. */
    public static final double MEAL_CARB_THRESHOLD_GRAMS = 10.0;
    /** A rapid bolus this close to a meal (either side) belongs to it. */
    public static final int ANCHOR_TOLERANCE_MINUTES = 30;
    /** How far back earlier carbs are searched for what is still on board at {@code t0}. */
    public static final int PRIOR_CARB_LOOKBACK_HOURS = 8;
    /** Below this the estimate divides by noise. */
    public static final double MIN_UNITS_NEEDED = 0.5;
    /** Plausible ratio band, same as the settings CHECK. */
    public static final double MIN_GRAMS_PER_UNIT = 3.0;
    public static final double MAX_GRAMS_PER_UNIT = 30.0;

    /** Why a meal gave no estimate; counted for the recompute log line. */
    enum Skip { UNBOLUSED, DUPLICATE, SICK, LATER_MEAL, NO_CGM, NO_ISF, TOO_FEW_UNITS, OUT_OF_RANGE }

    private final NoteRepository noteRepository;
    private final CgmReadingRepository cgmReadingRepository;
    private final UserInsulinPreferencesService userInsulinPreferencesService;
    private final UserSettingsService userSettingsService;
    private final CarbsOnBoardService carbsOnBoardService;
    private final CarbRatioMealWindowSnapshotRepository snapshotRepository;

    /** The cached profile: always 4 entries in {@link MealWindow#values()} order; missing rows have no data. */
    @Transactional(readOnly = true)
    public List<CarbRatioWindowEstimate> getProfile(UUID userId) {
        Map<MealWindow, CarbRatioMealWindowSnapshot> byWindow = new EnumMap<>(MealWindow.class);
        snapshotRepository.findByUserId(userId).forEach(s -> byWindow.put(s.getMealWindow(), s));

        List<CarbRatioWindowEstimate> profile = new ArrayList<>(MealWindow.values().length);
        for (MealWindow window : MealWindow.values()) {
            CarbRatioMealWindowSnapshot snap = byWindow.get(window);
            profile.add(snap == null
                    ? new CarbRatioWindowEstimate(window, null, 0)
                    : new CarbRatioWindowEstimate(window, snap.getGramsPerUnit(),
                            snap.getSampleCount() == null ? 0 : snap.getSampleCount()));
        }
        return profile;
    }

    /** Re-learns every window from scratch, upserts one row per window and returns {@link #getProfile}. */
    @Transactional
    public List<CarbRatioWindowEstimate> recomputeForUser(UUID userId) {
        RapidInsulinIobParameters rapid = userInsulinPreferencesService.getRapidIobParameters(userId);
        Function<String, RapidInsulinIobParameters> curveByCode = userInsulinPreferencesService.rapidCurveByCode(userId);
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);

        // Same LocalDateTime axis Hibernate uses for notes.timestamp (see IsfMealWindowProfileService).
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        LocalDateTime since = now.minusDays(HISTORY_DAYS);
        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(userId, since, now);
        List<CgmReading> cgm = cgmReadingRepository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(
                userId, IsfMealWindowProfileService.noteTimestampToEpochMs(since));

        Map<MealWindow, List<Double>> ratios = new EnumMap<>(MealWindow.class);
        for (MealWindow w : MealWindow.values()) ratios.put(w, new ArrayList<>());
        Map<Skip, Integer> skips = new EnumMap<>(Skip.class);
        int candidates = learn(notes, cgm, rapid, curveByCode, settings, ratios, skips);

        Map<MealWindow, Double> medians = new EnumMap<>(MealWindow.class);
        for (MealWindow window : MealWindow.values()) {
            List<Double> samples = ratios.get(window);
            Double gramsPerUnit = samples.size() >= MIN_MEALS ? median(samples) : null;
            medians.put(window, gramsPerUnit);
            CarbRatioMealWindowSnapshot snap = snapshotRepository.findByUserIdAndMealWindow(userId, window)
                    .orElseGet(() -> CarbRatioMealWindowSnapshot.builder().userId(userId).mealWindow(window).build());
            snap.setGramsPerUnit(gramsPerUnit);
            snap.setSampleCount(samples.size());
            snapshotRepository.save(snap);
        }

        log.info("Carb ratio profile recomputed user={} candidates={} breakfast={} lunch={} dinner={} night={} skipped={}",
                userId, candidates,
                summary(ratios, medians, MealWindow.BREAKFAST), summary(ratios, medians, MealWindow.LUNCH),
                summary(ratios, medians, MealWindow.DINNER), summary(ratios, medians, MealWindow.NIGHT),
                skips);

        return getProfile(userId);
    }

    /**
     * Walks the meals in time order and adds each clean meal's ratio to its window in {@code ratios}.
     *
     * @return the number of meals considered
     */
    private int learn(List<Note> notes, List<CgmReading> cgm, RapidInsulinIobParameters rapid,
                      Function<String, RapidInsulinIobParameters> curveByCode, UserSettingsDTO settings,
                      Map<MealWindow, List<Double>> ratios, Map<Skip, Integer> skips) {
        List<Note> meals = notes.stream()
                .filter(n -> n.getTimestamp() != null && !n.isHypoTreatment() && !n.isLongActing())
                .filter(n -> n.getCarbs() != null && n.getCarbs() >= MEAL_CARB_THRESHOLD_GRAMS)
                .sorted(Comparator.comparing(Note::getTimestamp))
                .toList();
        List<Note> rapidDoses = notes.stream()
                .filter(n -> n.getTimestamp() != null && !n.isLongActing())
                .filter(n -> n.getInsulin() != null && n.getInsulin() > 0)
                .toList();
        List<Note> carbNotes = notes.stream()
                .filter(n -> n.getTimestamp() != null && n.getCarbs() != null && n.getCarbs() > 0)
                .toList();
        Function<Note, RapidInsulinIobParameters> curveOf = d -> {
            RapidInsulinIobParameters c = curveByCode == null ? null : curveByCode.apply(d.getInsulinCode());
            return c != null ? c : rapid;
        };
        long diaMinutes = (long) (rapid.diaHours() * 60);

        Set<LocalDateTime> usedT0 = new HashSet<>();
        for (Note meal : meals) {
            LocalDateTime anchorFrom = meal.getTimestamp().minusMinutes(ANCHOR_TOLERANCE_MINUTES);
            LocalDateTime anchorTo = meal.getTimestamp().plusMinutes(ANCHOR_TOLERANCE_MINUTES);
            LocalDateTime t0 = rapidDoses.stream()
                    .map(Note::getTimestamp)
                    .filter(t -> !t.isBefore(anchorFrom) && !t.isAfter(anchorTo))
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (t0 == null) { skip(skips, Skip.UNBOLUSED); continue; }
            if (usedT0.contains(t0)) { skip(skips, Skip.DUPLICATE); continue; }
            if (settings != null && settings.getSickProfile() != null
                    && settings.getSickProfile().excludedFromLearning(t0)) { skip(skips, Skip.SICK); continue; }

            LocalDateTime tEnd = t0.plusMinutes(diaMinutes);
            LocalDateTime cleanFrom = t0.plusMinutes(ANCHOR_TOLERANCE_MINUTES);
            boolean laterMeal = meals.stream().map(Note::getTimestamp)
                    .anyMatch(t -> t.isAfter(cleanFrom) && !t.isAfter(tEnd));
            if (laterMeal) { skip(skips, Skip.LATER_MEAL); continue; }

            Double g0 = IsfMealWindowProfileService.nearestCgmMmol(cgm, t0);
            Double g1 = IsfMealWindowProfileService.nearestCgmMmol(cgm, tEnd);
            if (g0 == null || g1 == null) { skip(skips, Skip.NO_CGM); continue; }

            Double isfW = settings == null ? null : settings.getWindowIsf(t0);
            if (isfW == null || !Double.isFinite(isfW) || isfW <= 0) { skip(skips, Skip.NO_ISF); continue; }

            double carbs = carbsAbsorbed(carbNotes, t0, tEnd, settings);
            double insulin = insulinActed(rapidDoses, t0, tEnd, curveOf);
            double needed = insulin + (g1 - g0) / isfW;
            if (needed < MIN_UNITS_NEEDED) { skip(skips, Skip.TOO_FEW_UNITS); continue; }

            double ratio = carbs / needed;
            if (!Double.isFinite(ratio) || ratio < MIN_GRAMS_PER_UNIT || ratio > MAX_GRAMS_PER_UNIT) {
                skip(skips, Skip.OUT_OF_RANGE);
                continue;
            }

            usedT0.add(t0);
            MealWindow.fromTimestamp(t0).ifPresent(w -> ratios.get(w).add(ratio));
        }
        return meals.size();
    }

    /**
     * Carbs absorbed between {@code t0} and {@code tEnd}: every entry in {@code [t0 − 30 min, tEnd]}
     * (rescue carbs too), plus what earlier entries still had on board at {@code t0}, minus what all
     * of them still have on board at {@code tEnd}.
     */
    private double carbsAbsorbed(List<Note> carbNotes, LocalDateTime t0, LocalDateTime tEnd, UserSettingsDTO settings) {
        LocalDateTime windowStart = t0.minusMinutes(ANCHOR_TOLERANCE_MINUTES);
        LocalDateTime priorStart = t0.minusHours(PRIOR_CARB_LOOKBACK_HOURS);
        double windowGrams = 0.0;
        List<CarbsEntry> windowEntries = new ArrayList<>();
        List<CarbsEntry> priorEntries = new ArrayList<>();
        for (Note n : carbNotes) {
            LocalDateTime t = n.getTimestamp();
            if (!t.isBefore(windowStart) && !t.isAfter(tEnd)) {
                windowGrams += n.getCarbs();
                windowEntries.add(IsfMealWindowProfileService.buildCarbsEntry(n));
            } else if (!t.isBefore(priorStart) && t.isBefore(windowStart)) {
                priorEntries.add(IsfMealWindowProfileService.buildCarbsEntry(n));
            }
        }
        double priorCobAtStart = priorEntries.isEmpty() ? 0.0
                : carbsOnBoardService.calculateTotalCarbsOnBoard(priorEntries, t0, settings);
        List<CarbsEntry> all = new ArrayList<>(windowEntries);
        all.addAll(priorEntries);
        double cobAtEnd = carbsOnBoardService.calculateTotalCarbsOnBoard(all, tEnd, settings);
        return windowGrams + priorCobAtStart - cobAtEnd;
    }

    /**
     * Insulin that acted between {@code t0} and {@code tEnd}: on board at {@code t0} from earlier
     * doses, the doses at {@code t0} in full, and each later dose up to {@code tEnd} minus what it
     * still has on board at {@code tEnd}.
     */
    private static double insulinActed(List<Note> rapidDoses, LocalDateTime t0, LocalDateTime tEnd,
                                       Function<Note, RapidInsulinIobParameters> curveOf) {
        double units = 0.0;
        for (Note d : rapidDoses) {
            LocalDateTime t = d.getTimestamp();
            if (t.isBefore(t0)) {
                units += iob(d, Duration.between(t, t0).toMinutes(), curveOf.apply(d));
            } else if (t.isEqual(t0)) {
                units += d.getInsulin();
            } else if (!t.isAfter(tEnd)) {
                units += d.getInsulin() - iob(d, Duration.between(t, tEnd).toMinutes(), curveOf.apply(d));
            }
        }
        return units;
    }

    private static double iob(Note dose, long minutesAgo, RapidInsulinIobParameters curve) {
        return InsulinCalculatorService.iobOpenApsExponential(
                dose.getInsulin(), minutesAgo, curve.diaHours(), curve.peakMinutes());
    }

    /** Median; the mean of the two middle values for an even count. */
    static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    private static void skip(Map<Skip, Integer> skips, Skip reason) {
        skips.merge(reason, 1, Integer::sum);
    }

    private static String summary(Map<MealWindow, List<Double>> ratios, Map<MealWindow, Double> medians, MealWindow w) {
        Double gpu = medians.get(w);
        return String.format("[gpu=%s n=%d]", gpu == null ? "-" : String.format("%.2f", gpu), ratios.get(w).size());
    }
}
