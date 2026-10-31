package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.DosingRefusedException;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.service.nutrition.GlycemicPatternMatchingService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the meal card: nutrition ranges, the calculator's dose (target and IOB from the user's
 * own settings and logged insulin) and the dashboard-forecast pause. Without a fresh CGM reading
 * only the nutrition is returned. A calculator refusal surfaces as {@code refusalCode} with no pause.
 */
@Service
public class MealPlanService {

    /** Used only when the user has no target setting. */
    static final double DEFAULT_TARGET_MMOL = 5.5;
    /** The dose is rounded DOWN to this step: never more insulin than the calculator asked for. */
    static final double DOSE_STEP = 0.5;

    private final CgmFreshnessService freshness;
    private final GlucoseCalculationsService calculations;
    private final InsulinCalculatorService calculator;
    private final PreBolusPauseOptimizer optimizer;
    private final GlycemicPatternMatchingService patterns;
    private final UserSettingsService settings;
    private final Clock clock;

    @Autowired
    public MealPlanService(CgmFreshnessService freshness, GlucoseCalculationsService calculations,
                           InsulinCalculatorService calculator, PreBolusPauseOptimizer optimizer,
                           GlycemicPatternMatchingService patterns, UserSettingsService settings) {
        this(freshness, calculations, calculator, optimizer, patterns, settings, Clock.systemUTC());
    }

    MealPlanService(CgmFreshnessService freshness, GlucoseCalculationsService calculations,
                    InsulinCalculatorService calculator, PreBolusPauseOptimizer optimizer,
                    GlycemicPatternMatchingService patterns, UserSettingsService settings, Clock clock) {
        this.freshness = freshness;
        this.calculations = calculations;
        this.calculator = calculator;
        this.optimizer = optimizer;
        this.patterns = patterns;
        this.settings = settings;
        this.clock = clock;
    }

    /** @param now the user's wall time (calculator meal windows, optimizer); freshness uses the clock. */
    public MealPlan plan(UUID userId, NutritionSnapshot n, LocalDateTime now) {
        if (n.getPatternName() == null) {
            patterns.enrich(n);
        }
        Double conf = n.getConfidence();
        double carbs = v(n.getTotalCarbs());
        double fat = v(n.getFat());
        double protein = v(n.getProtein());
        double fiber = v(n.getFiber());
        NutritionRange rc = NutritionRange.of(carbs, conf);
        NutritionRange rf = NutritionRange.of(fat, conf);
        NutritionRange rp = NutritionRange.of(protein, conf);
        NutritionRange rfi = NutritionRange.of(fiber, conf);
        String label = n.getPatternName();

        Optional<CgmReadingDTO> reading = freshness.freshReading(userId, clock.instant());
        if (reading.isEmpty()) {
            return new MealPlan(rc, rf, rp, rfi, n.getEstimatedGi(), label, null, null, null, true);
        }
        double glucose = reading.get().mmol();

        UserSettingsDTO s = settings.getUserSettings(userId);
        double target = s != null && s.getTargetGlucoseMmol() != null ? s.getTargetGlucoseMmol() : DEFAULT_TARGET_MMOL;

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

        PauseChoice pause = optimizer.choose(userId, now, glucose, dose,
                new MealMacros(carbs, fat, protein, fiber, n.getEstimatedGi()));
        return new MealPlan(rc, rf, rp, rfi, n.getEstimatedGi(), label, dose, null, pause, false);
    }

    private static double v(Double d) {
        return d == null ? 0.0 : d;
    }
}
