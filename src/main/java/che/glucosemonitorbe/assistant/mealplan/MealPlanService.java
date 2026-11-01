package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.assistant.episode.Situation;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.service.nutrition.GlycemicPatternMatchingService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import che.glucosemonitorbe.service.observer.HypoThresholds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the meal card: nutrition ranges, the calculator's dose (target and IOB from the user's
 * own settings and logged insulin) and the dashboard-forecast pause. Without a fresh CGM reading
 * only the nutrition is returned. A calculator refusal surfaces as {@code refusalCode} with no pause.
 * A dose whose own forecast (insulin on board + the meal + this dose) goes below
 * {@link HypoThresholds#HYPO_MMOL} is withheld as {@link DosingRefusalReason#FORECAST_LOW}; the pause
 * stays on the plan so the card and the reply can show how low it goes.
 * A plan with a dose may carry the history-dose banner (spec 6.6), priced with the calculator's own terms.
 */
@Service
public class MealPlanService {

    /** The dose is rounded DOWN to this step: never more insulin than the calculator asked for. */
    static final double DOSE_STEP = 0.5;

    private final CgmFreshnessService freshness;
    private final GlucoseCalculationsService calculations;
    private final InsulinCalculatorService calculator;
    private final PreBolusPauseOptimizer optimizer;
    private final GlycemicPatternMatchingService patterns;
    private final UserSettingsService settings;
    private final HistoryDoseService history;
    private final Clock clock;

    @Autowired
    public MealPlanService(CgmFreshnessService freshness, GlucoseCalculationsService calculations,
                           InsulinCalculatorService calculator, PreBolusPauseOptimizer optimizer,
                           GlycemicPatternMatchingService patterns, UserSettingsService settings,
                           HistoryDoseService history) {
        this(freshness, calculations, calculator, optimizer, patterns, settings, history, Clock.systemUTC());
    }

    MealPlanService(CgmFreshnessService freshness, GlucoseCalculationsService calculations,
                    InsulinCalculatorService calculator, PreBolusPauseOptimizer optimizer,
                    GlycemicPatternMatchingService patterns, UserSettingsService settings,
                    HistoryDoseService history, Clock clock) {
        this.freshness = freshness;
        this.calculations = calculations;
        this.calculator = calculator;
        this.optimizer = optimizer;
        this.patterns = patterns;
        this.settings = settings;
        this.history = history;
        this.clock = clock;
    }

    /** @param now the user's wall time (calculator meal windows, optimizer); freshness uses the clock. */
    public MealPlan plan(UUID userId, NutritionSnapshot n, LocalDateTime now) {
        if (n.getPatternName() == null) {
            patterns.enrich(n);
        }
        Double conf = n.getConfidence();
        if (conf != null && (!Double.isFinite(conf) || conf < 0.0 || conf > 1.0)) {
            conf = null; // unusable confidence = widest band
        }
        double carbs = v(n.getTotalCarbs());
        double fat = v(n.getFat());
        double protein = v(n.getProtein());
        double fiber = v(n.getFiber());
        NutritionRange rc = NutritionRange.of(carbs, conf);
        NutritionRange rf = NutritionRange.of(fat, conf);
        NutritionRange rp = NutritionRange.of(protein, conf);
        NutritionRange rfi = NutritionRange.of(fiber, conf);
        String label = n.getPatternName();

        if (invalid(n.getTotalCarbs()) || invalid(n.getFat()) || invalid(n.getProtein()) || invalid(n.getFiber())) {
            // A negative meal would cancel a correction; never reach the calculator with it.
            NutritionRange zero = new NutritionRange(0, 0, 0);
            return new MealPlan(zero, zero, zero, zero, n.getEstimatedGi(), label, null,
                    DosingRefusalReason.INVALID_INPUT.name(), null, false);
        }

        Optional<CgmReadingDTO> reading = freshness.freshReading(userId, clock.instant());
        if (reading.isEmpty()) {
            return new MealPlan(rc, rf, rp, rfi, n.getEstimatedGi(), label, null, null, null, true);
        }
        double glucose = reading.get().mmol();

        UserSettingsDTO s = settings.getUserSettings(userId);
        double target = s != null && s.getTargetGlucoseMmol() != null ? s.getTargetGlucoseMmol()
                : UserSettingsDTO.DEFAULT_TARGET_GLUCOSE_MMOL;

        GlucoseCalculationsService.ActiveCobIobInputs inputs = calculations.activeCobIobInputs(userId, now);
        double iob = calculator.calculateTotalActiveInsulin(inputs.insulinEntries(), now,
                inputs.rapidIob().diaHours(), inputs.rapidIob().peakMinutes());
        iob = Math.round(iob * 100.0) / 100.0;

        double dose;
        try {
            double raw = calculator.calculateRecommendedInsulin(InsulinCalculationRequest.builder()
                    .carbs(carbs)
                    .currentGlucose(glucose)
                    .targetGlucose(target)
                    .activeInsulin(iob)
                    .userId(userId.toString())
                    .clientTimeInfo(ClientTimeInfo.builder().timestamp(now.toString()).build())
                    .build()).getRecommendedInsulin();
            dose = Math.floor(Math.max(0.0, raw) / DOSE_STEP + 1e-9) * DOSE_STEP;
        } catch (DosingRefusedException e) {
            return new MealPlan(rc, rf, rp, rfi, n.getEstimatedGi(), label, null, e.getReason().name(), null, false);
        }

        MealMacros macros = new MealMacros(carbs, fat, protein, fiber, n.getEstimatedGi());
        PauseChoice pause = optimizer.choose(userId, now, glucose, dose, macros);
        if (dose > 0 && pause != null && pause.min() != null && pause.min() < HypoThresholds.HYPO_MMOL) {
            // The calculator only sees carbs, glucose and IOB; the forecast also sees when they act.
            return new MealPlan(rc, rf, rp, rfi, n.getEstimatedGi(), label, null,
                    DosingRefusalReason.FORECAST_LOW.name(), pause, false);
        }
        HistoryDose banner = historyDose(userId, n, macros, now, glucose, target, iob, dose, s);
        return new MealPlan(rc, rf, rp, rfi, n.getEstimatedGi(), label, dose, null, pause, false, banner);
    }

    /** Only for a real dose; correction and IOB are exactly the terms the calculator just applied. */
    private HistoryDose historyDose(UUID userId, NutritionSnapshot n, MealMacros macros, LocalDateTime now,
                                    double glucose, double target, double iob, double dose, UserSettingsDTO s) {
        if (!(dose > 0) || s == null) return null;
        Double isfNow = s.getEffectiveIsf(now);
        if (isfNow == null || !Double.isFinite(isfNow) || isfNow <= 0) return null;
        double correction = (glucose - target) / isfNow;
        Situation situation = new Situation(foodText(n), macros, now.getHour() * 60 + now.getMinute(),
                glucose, iob, 0);
        return history.evaluate(userId, situation, dose, correction, iob, macros.carbs()).orElse(null);
    }

    private static String foodText(NutritionSnapshot n) {
        if (n.getFoodMassBreakdown() != null) {
            String labels = n.getFoodMassBreakdown().stream().filter(Objects::nonNull)
                    .map(NutritionSnapshot.FoodMassBreakdown::getLabel).filter(MealPlanService::present)
                    .collect(Collectors.joining(", "));
            if (!labels.isEmpty()) return labels;
        }
        List<String> foods = n.getNormalizedFoods();
        if (foods != null) {
            String joined = foods.stream().filter(MealPlanService::present).collect(Collectors.joining(", "));
            if (!joined.isEmpty()) return joined;
        }
        return present(n.getPatternName()) ? n.getPatternName() : "meal";
    }

    private static boolean present(String v) {
        return v != null && !v.isBlank();
    }

    private static boolean invalid(Double d) {
        return d != null && (!Double.isFinite(d) || d < 0.0);
    }

    private static double v(Double d) {
        return d == null ? 0.0 : d;
    }
}
