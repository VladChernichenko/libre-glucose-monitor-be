package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.assistant.episode.EpisodeSearchService;
import che.glucosemonitorbe.assistant.episode.MealEpisode;
import che.glucosemonitorbe.assistant.episode.ScoredEpisode;
import che.glucosemonitorbe.assistant.episode.Situation;
import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.dto.IsfMealWindowSuggestionDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.IsfMealWindowSuggestionService;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * History dose and the 20% banner (spec 6.6). Backend arithmetic only, never the LLM.
 *
 * <p>The top {@value #QUALIFYING_LIMIT} qualifying similar episodes are used (not contaminated, own
 * dose > 0, a start reading, complete 4 h CGM, a valid ISF at their time); per episode i:
 * <pre>
 * ideal_i = dose_i + (g240_i - target) / ISF_i
 * if nadir_i &lt; 3.9: ideal_i = min(ideal_i, dose_i - (target - nadir_i) / ISF_i)
 * meal_i  = ideal_i - (start_i - target) / ISF_i + iob_i      (dropped if &lt;= 0)
 * upg_i   = meal_i / carbs_i
 * units   = roundHalf(median(upg) x carbs + todayCorrection - todayIob), floored at 0
 * show iff count &gt;= 3 AND |units - model| / model &gt; 0.20 AND |units - model| &gt;= 0.5
 *          AND units &lt;= max bolus
 * </pre>
 * ISF_i is the ISF the calculator would have used at the episode's wall time. An episode with unknown
 * IOB counts it as 0, the side that lowers meal_i (and so the history dose).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryDoseService {

    public static final String TOGGLE = "history-dose-enabled";
    /** The history check uses the top 10 QUALIFYING episodes (spec 6.7), in similarity order. */
    static final int QUALIFYING_LIMIT = 10;
    /**
     * Candidates requested from the search before filtering: covers the whole scored pool (the union of
     * the cosine and macro top-50 lists), so contaminated, undosed or gappy meals do not crowd out
     * qualifying ones further down.
     */
    static final int CANDIDATE_LIMIT = 100;
    static final int MIN_EPISODES = 3;
    /** "Complete 4 h CGM": g240 present and no CGM gap longer than this in the window [min]. */
    static final int MAX_CGM_GAP_MIN = 30;
    static final double HYPO_MMOL = 3.9;
    static final double BANNER_MIN_RELATIVE = 0.20;
    static final double BANNER_MIN_UNITS = 0.5;
    static final double STEP = 0.5;

    private final EpisodeSearchService search;
    private final UserSettingsService settingsService;
    private final InsulinCalculatorService calculator;
    private final IsfMealWindowSuggestionService isfSuggestions;
    private final FeatureToggleService toggles;

    /**
     * @param modelDose       the plan's dose (U), the number the banner compares with
     * @param todayCorrection (glucose now - target) / ISF now, exactly as the calculator applied it
     * @param todayIob        the IOB the calculator subtracted
     * @param carbs           grams of the meal being planned
     * @return the banner, or empty when it should not show (or anything needed is missing)
     */
    public Optional<HistoryDose> evaluate(UUID userId, Situation s, double modelDose, double todayCorrection,
                                          double todayIob, double carbs) {
        if (!toggles.isEnabled(TOGGLE)) return Optional.empty();
        if (!(modelDose > 0) || !(carbs > 0) || !Double.isFinite(todayCorrection) || !Double.isFinite(todayIob)) {
            return Optional.empty();
        }
        try {
            return compute(userId, s, modelDose, todayCorrection, todayIob, carbs);
        } catch (RuntimeException e) {
            // The banner is advisory; its failure must never fail the meal card.
            log.warn("History dose skipped for userId={}: {}", userId, e.toString());
            return Optional.empty();
        }
    }

    private Optional<HistoryDose> compute(UUID userId, Situation s, double modelDose, double todayCorrection,
                                          double todayIob, double carbs) {
        UserSettingsDTO settings = settingsService.getUserSettings(userId);
        if (settings == null) return Optional.empty();
        double target = settings.getTargetGlucoseMmol() != null ? settings.getTargetGlucoseMmol()
                : UserSettingsDTO.DEFAULT_TARGET_GLUCOSE_MMOL;

        List<Double> upg = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        int taken = 0;
        for (ScoredEpisode se : search.similar(userId, s, CANDIDATE_LIMIT)) {
            if (taken >= QUALIFYING_LIMIT) break;
            MealEpisode e = se.episode();
            if (!qualifies(e)) continue;
            Double isf = settings.getEffectiveIsf(e.getMealAt());
            if (isf == null || !Double.isFinite(isf) || isf <= 0) continue;
            taken++; // one of the top 10 qualifying; the meal <= 0 drop applies within them
            double dose = e.getDoseUnits();
            double ideal = dose + (e.getG240() - target) / isf;
            if (e.getNadir() != null && e.getNadir() < HYPO_MMOL) {
                ideal = Math.min(ideal, dose - (target - e.getNadir()) / isf);
            }
            double iob = e.getIob() != null && Double.isFinite(e.getIob()) ? e.getIob() : 0.0;
            double meal = ideal - (e.getStartGlucose() - target) / isf + iob;
            if (!(meal > 0)) continue; // unreliable episode
            upg.add(meal / e.getCarbs());
            ids.add(e.getId());
        }
        if (upg.size() < MIN_EPISODES) return Optional.empty();

        double raw = median(upg) * carbs + todayCorrection - todayIob;
        double units = Math.max(0.0, roundHalf(raw));
        double diff = Math.abs(units - modelDose);
        if (diff / modelDose <= BANNER_MIN_RELATIVE || diff < BANNER_MIN_UNITS) return Optional.empty();
        if (units > calculator.maxBolusUnits(settings)) return Optional.empty();

        double deltaPct = Math.round((units - modelDose) / modelDose * 1000.0) / 10.0;
        return Optional.of(new HistoryDose(units, modelDose, deltaPct, List.copyOf(ids),
                pendingIsfNote(userId, s.minuteOfDay())));
    }

    static boolean qualifies(MealEpisode e) {
        return !e.isContaminated()
                && e.getDoseUnits() != null && e.getDoseUnits() > 0
                && e.getStartGlucose() > 0 && Double.isFinite(e.getStartGlucose())
                && e.getG240() != null && Double.isFinite(e.getG240())
                && e.getCgmMaxGapMin() != null && e.getCgmMaxGapMin() <= MAX_CGM_GAP_MIN
                && e.getCarbs() > 0;
    }

    private String pendingIsfNote(UUID userId, int minuteOfDay) {
        Optional<MealWindow> window = MealWindow.fromHour(Math.floorMod(minuteOfDay, 1440) / 60);
        if (window.isEmpty()) return null;
        try {
            IsfMealWindowSuggestionDTO sug = isfSuggestions.getSuggestion(userId);
            if (sug == null || !sug.isShow() || sug.getWindows() == null) return null;
            boolean pending = sug.getWindows().stream().anyMatch(w ->
                    window.get().name().equals(w.getMealWindow()) && w.isHasData()
                            && w.getProposedIsf() != null && w.getCurrentIsf() != null
                            && Math.abs(w.getProposedIsf() - w.getCurrentIsf())
                            >= IsfMealWindowSuggestionService.MIN_DELTA_ISF);
            return pending ? "An ISF change for " + window.get().name().toLowerCase(Locale.ROOT)
                    + " is also suggested on the dashboard." : null;
        } catch (RuntimeException e) {
            log.warn("ISF suggestion lookup failed for userId={}: {}", userId, e.toString());
            return null;
        }
    }

    static double roundHalf(double v) {
        return Math.round(v / STEP) * STEP;
    }

    static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
