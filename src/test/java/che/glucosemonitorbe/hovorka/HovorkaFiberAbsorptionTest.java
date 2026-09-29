package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Dietary fiber must shape the dashboard (Hovorka) curve, not just the {@code /api/predict} one.
 *
 * <h3>Gap</h3>
 * {@link MacroNutrientGastricModel} slows absorption by {@code exp(0.02 × fiber g)} on the
 * {@code /api/predict} path, but the notes-driven dashboard path never read {@code fiber}: a lentil
 * meal and white rice with the same grams and GI drew identical curves, and a salad eaten before
 * the carbs ("vegetables first", Shukla et al.) changed nothing.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Fiber lengthens the meal's gut time constant by the same viscosity factor as
 *       {@code /api/predict} - shape only: the peak is lower and later, the total absorbed is the
 *       same (fiber never lowers the carb count / dose).</li>
 *   <li>Fiber eaten up to {@link HovorkaGlucosePredictionService#FIRST_COURSE_WINDOW_MIN} before a
 *       carb meal (a first course) slows that meal too.</li>
 *   <li>A meal with no fiber draws exactly the curve it always did.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class HovorkaFiberAbsorptionTest {

    @Mock HovorkaParameterService       paramService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock UserSettingsService           userSettingsService;

    private HovorkaGlucosePredictionService service;
    private HovorkaParameters params;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 1, 13, 0);
    private static final double START_GLUCOSE = 6.0;
    private static final double CARBS = 50.0;
    private static final double GI = 55.0;

    @BeforeEach
    void setUp() {
        DallaManGutModel gutModel  = new DallaManGutModel();
        HovorkaOdeSolver solver    = new HovorkaOdeSolver(gutModel);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        service = new HovorkaGlucosePredictionService(
                paramService, solver, basal, insulinPrefsService, gutModel, userSettingsService,
                PredictionResidualProvider.NONE);

        double weight = 70.0;
        params = new HovorkaParameters(
                HovorkaParameters.VG_PER_KG  * weight,
                HovorkaParameters.F01_PER_KG * weight,
                HovorkaParameters.F01_PER_KG * weight,
                HovorkaParameters.EGP0_PER_KG * weight,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G,
                1.0, 2.2, weight);

        lenient().when(insulinPrefsService.getRapidIobParameters(any()))
                .thenReturn(new RapidInsulinIobParameters(4.5, 55.0));
    }

    // -- Fiber in the meal itself ------------------------------------------------

    @Test
    @DisplayName("a fiber-rich meal peaks lower and no earlier than the same carbs without fiber")
    void fiberRichMeal_peaksLowerAndNoEarlier() {
        List<PredictionPointDTO> plain = curve(List.of(meal(0, 0.0)));
        List<PredictionPointDTO> fibrous = curve(List.of(meal(0, 15.0)));

        assertThat(peakCarbEffect(fibrous))
                .as("15 g fiber must blunt the glucose-appearance peak. fiber=%.3f plain=%.3f",
                        peakCarbEffect(fibrous), peakCarbEffect(plain))
                .isLessThan(peakCarbEffect(plain));
        assertThat(peakCarbEffectMinute(fibrous))
                .as("and must not move it earlier")
                .isGreaterThanOrEqualTo(peakCarbEffectMinute(plain));
        assertThat(maxGlucose(fibrous))
                .as("so the glucose peak itself is lower. fiber=%.2f plain=%.2f",
                        maxGlucose(fibrous), maxGlucose(plain))
                .isLessThan(maxGlucose(plain));
    }

    @Test
    @DisplayName("fiber changes the shape, not the amount: total absorbed stays the same")
    void fiberRichMeal_absorbsTheSameTotal() {
        // 24 h so both meals finish absorbing: at 8 h the slower fiber meal still has ~10 % in the
        // gut, which is exactly the delay being modelled, not carbs going missing.
        double plain = totalCarbEffect(curve(List.of(meal(0, 0.0)), 1440));
        double fibrous = totalCarbEffect(curve(List.of(meal(0, 15.0)), 1440));

        assertThat(fibrous)
                .as("fiber slows absorption; it must not silently act as a carb/dose reduction. "
                        + "fiber=%.2f plain=%.2f", fibrous, plain)
                .isCloseTo(plain, within(plain * 0.05));
    }

    @Test
    @DisplayName("an already-eaten fiber meal is slowed in the warm-up replay too")
    void fiberRichMealAlreadyEaten_isSlowedInTheWarmUp() {
        // Both meals are 30 min old: only buildWarmState sees them.
        List<PredictionPointDTO> plain = curve(List.of(meal(30, 0.0)));
        List<PredictionPointDTO> fibrous = curve(List.of(meal(30, 15.0)));

        assertThat(carbEffectAt(fibrous, 5))
                .as("30 min in, the fiber meal must be absorbing more slowly. fiber=%.3f plain=%.3f",
                        carbEffectAt(fibrous, 5), carbEffectAt(plain, 5))
                .isLessThan(carbEffectAt(plain, 5));
    }

    @Test
    @DisplayName("a fiber meal one minute old predicts like the same meal one minute ahead")
    void fiberMealJustEaten_matchesFiberMealJustAhead() {
        List<PredictionPointDTO> behind = curve(List.of(mealAt(NOW.minusMinutes(1), 15.0)));
        List<PredictionPointDTO> ahead  = curve(List.of(mealAt(NOW.plusMinutes(1), 15.0)));

        assertThat(glucoseAt(behind, 120))
                .as("warm-up and forward paths must agree on fiber")
                .isCloseTo(glucoseAt(ahead, 120), within(0.3));
    }

    @Test
    @DisplayName("a meal with no fiber draws exactly the curve it did before fiber was modelled")
    void noFiber_isBitIdenticalToNullFiber() {
        CarbsEntry nullFiber = mealAt(NOW, 0.0);
        nullFiber.setFiber(null);

        assertThat(glucoseSeries(curve(List.of(mealAt(NOW, 0.0)))))
                .isEqualTo(glucoseSeries(curve(List.of(nullFiber))));
    }

    // -- Fiber first course ("vegetables first") ---------------------------------

    @Test
    @DisplayName("a salad eaten 10 min before the carbs blunts the carb peak")
    void fiberFirstCourse_bluntsTheFollowingMeal() {
        List<PredictionPointDTO> mealOnly = curve(List.of(meal(0, 0.0)));
        List<PredictionPointDTO> saladFirst = curve(List.of(salad(NOW.minusMinutes(10), 8.0), meal(0, 0.0)));

        assertThat(maxGlucose(saladFirst))
                .as("fiber eaten first must slow the carbs that follow. salad-first=%.2f meal-only=%.2f",
                        maxGlucose(saladFirst), maxGlucose(mealOnly))
                .isLessThan(maxGlucose(mealOnly));
    }

    @Test
    @DisplayName("a salad eaten long before the meal does not count as a first course")
    void fiberFirstCourse_outsideTheWindow_hasNoEffect() {
        int longBefore = HovorkaGlucosePredictionService.FIRST_COURSE_WINDOW_MIN + 30;
        List<PredictionPointDTO> mealOnly = curve(List.of(meal(0, 0.0)));
        List<PredictionPointDTO> saladEarlier = curve(List.of(salad(NOW.minusMinutes(longBefore), 8.0), meal(0, 0.0)));

        assertThat(glucoseSeries(saladEarlier)).isEqualTo(glucoseSeries(mealOnly));
    }

    // -- helpers -----------------------------------------------------------------

    private CarbsEntry meal(int minsAgo, double fiberG) {
        return mealAt(NOW.minusMinutes(minsAgo), fiberG);
    }

    private CarbsEntry mealAt(LocalDateTime when, double fiberG) {
        CarbsEntry e = CarbsEntry.builder().timestamp(when).carbs(CARBS).build();
        e.setEstimatedGi(GI);
        e.setFiber(fiberG);
        return e;
    }

    /** A fiber-only first course: no carbs, protein or fat, so fiber is the only lever measured. */
    private CarbsEntry salad(LocalDateTime when, double fiberG) {
        CarbsEntry e = CarbsEntry.builder().timestamp(when).carbs(0.0).build();
        e.setFiber(fiberG);
        return e;
    }

    private List<PredictionPointDTO> curve(List<CarbsEntry> entries) {
        return curve(entries, 240);
    }

    private List<PredictionPointDTO> curve(List<CarbsEntry> entries, int pathMinutes) {
        return service.buildPredictionPath(
                params, START_GLUCOSE, NOW,
                new ArrayList<>(entries), List.<InsulinDose>of(), List.<Note>of(),
                USER_ID, pathMinutes);
    }

    private double glucoseAt(List<PredictionPointDTO> curve, int minutesFromNow) {
        LocalDateTime target = NOW.plusMinutes(minutesFromNow);
        return curve.stream().filter(pt -> pt.getTimestamp().equals(target))
                .mapToDouble(PredictionPointDTO::getPredictedGlucose).findFirst()
                .orElseThrow(() -> new AssertionError("no point at +" + minutesFromNow + " min"));
    }

    private double carbEffectAt(List<PredictionPointDTO> curve, int minutesFromNow) {
        LocalDateTime target = NOW.plusMinutes(minutesFromNow);
        return curve.stream().filter(pt -> pt.getTimestamp().equals(target))
                .mapToDouble(PredictionPointDTO::getCarbAbsorptionEffect).findFirst()
                .orElseThrow(() -> new AssertionError("no point at +" + minutesFromNow + " min"));
    }

    private static List<Double> glucoseSeries(List<PredictionPointDTO> curve) {
        return curve.stream().map(PredictionPointDTO::getPredictedGlucose).toList();
    }

    private static double maxGlucose(List<PredictionPointDTO> curve) {
        return curve.stream().mapToDouble(PredictionPointDTO::getPredictedGlucose).max().orElseThrow();
    }

    private static double peakCarbEffect(List<PredictionPointDTO> curve) {
        return curve.stream().mapToDouble(PredictionPointDTO::getCarbAbsorptionEffect).max().orElseThrow();
    }

    private int peakCarbEffectMinute(List<PredictionPointDTO> curve) {
        PredictionPointDTO peak = curve.stream()
                .max(Comparator.comparingDouble(PredictionPointDTO::getCarbAbsorptionEffect)).orElseThrow();
        return (int) Duration.between(NOW, peak.getTimestamp()).toMinutes();
    }

    /**
     * Total glucose appearance, each emitted effect weighted by its emission step (5 min to 4 h,
     * 10 min after) so the sparse tail is not under-counted.
     */
    private double totalCarbEffect(List<PredictionPointDTO> curve) {
        double total = 0.0;
        LocalDateTime prev = NOW;
        for (PredictionPointDTO pt : curve) {
            long step = Duration.between(prev, pt.getTimestamp()).toMinutes();
            total += pt.getCarbAbsorptionEffect() * (step / 5.0);
            prev = pt.getTimestamp();
        }
        return total;
    }
}
