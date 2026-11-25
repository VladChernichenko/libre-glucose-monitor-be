package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.TestWindowRatios;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.sick.SickProfile;
import che.glucosemonitorbe.sick.SickSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * A meal dosed exactly by the user's insulin-to-carb ratio must not move the forecast's landing,
 * whichever meal window it is eaten in. Before meals were sized by their window's ISF, a lunch
 * override of 1.47 against a base ISF of 1.0 made every ratio-priced lunch dose forecast ~3-5
 * mmol/L low, and the meal card withheld the dose as FORECAST_LOW. A_G is the neutral magnitude, as
 * {@link HovorkaParameterService#buildForUser} builds it; each meal is sized by its window's ratio.
 */
@ExtendWith(MockitoExtension.class)
class HovorkaMealWindowRatioTest {

    @Mock HovorkaParameterService paramService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock UserSettingsService userSettingsService;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final double BASE_ISF = 1.0;
    private static final double GRAMS_PER_UNIT = 10.0;
    private static final double START = 7.0;
    private static final double CARBS = 60.0;

    private HovorkaGlucosePredictionService service;
    private HovorkaParameters params;

    @BeforeEach
    void setUp() {
        DallaManGutModel gutModel = new DallaManGutModel();
        service = new HovorkaGlucosePredictionService(
                paramService, new HovorkaOdeSolver(gutModel), new BasalInsulinResolver(),
                insulinPrefsService, gutModel, userSettingsService, PredictionResidualProvider.NONE);
        double weight = 84.0;
        double vG = HovorkaParameters.VG_PER_KG * weight;
        double f01 = HovorkaParameters.F01_PER_KG * weight;
        double aG = HovorkaParameterService.mealMagnitude(BASE_ISF, vG, null);
        params = new HovorkaParameters(vG, f01, f01, HovorkaParameters.EGP0_PER_KG * weight,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP, 45.0 / 1.68, aG, BASE_ISF, weight);
        lenient().when(insulinPrefsService.getRapidIobParameters(any()))
                .thenReturn(new RapidInsulinIobParameters(4.0, 55.0));
    }

    @ParameterizedTest(name = "{0} window, ISF {2}")
    @CsvSource(nullValues = "none", value = {
            "breakfast,   8,  1.50",
            "lunch,       12, 1.47",
            "dinner,      18, 2.76",
            "night,       23, 1.88",
            "no override, 12, none"})
    void aRatioPricedMealLandsWhereItStarted(String window, int hour, Double windowIsf) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, hour, 0);
        UserSettingsDTO settings = settings(windowIsf);
        double units = CARBS / GRAMS_PER_UNIT;   // what the calculator charges for the carbs alone

        double landing = landing(settings, now, units, 0, 480);

        assertThat(landing).as("%s: a ratio-priced meal must not move the landing", window)
                .isCloseTo(START, within(0.6));
    }

    @ParameterizedTest(name = "{0}: ISF {2}, ratio {3}")
    @CsvSource(nullValues = "none", value = {
            "breakfast, 8,  1.50, 6.0",
            "lunch,     12, 1.47, 12.0",
            "dinner,    18, none, 8.0",
            "night,     23, 1.88, none"})
    void aMealPricedByItsWindowRatioLandsWhereItStarted(String w, int hour, Double isf, Double ratio) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, hour, 0);
        UserSettingsDTO settings = settings(isf, ratio);
        double units = CARBS / (ratio != null ? ratio : GRAMS_PER_UNIT);

        double landing = landing(settings, now, units, 0, 480);

        assertThat(landing).as("%s: a window-ratio-priced meal must not move the landing", w)
                .isCloseTo(START, within(0.6));
    }

    @Test
    void aBreakfastMealIsSizedByIsfOverRatio() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 8, 0);

        double magnitude = params.aG() * HovorkaGlucosePredictionService.mealWindowFactor(now, params, settings(1.5, 6.0));

        assertThat(magnitude).isCloseTo(1.5 * HovorkaParameterService.mealMagnitude(BASE_ISF, params.vG(), 6.0),
                within(1e-9));
        // the same meal size a 10 g/U A_G rescaled by 10/6 gave when one ratio shaped A_G
        assertThat(magnitude).isCloseTo(
                1.5 * 10.0 / 6.0 * HovorkaParameterService.mealMagnitude(BASE_ISF, params.vG(), 10.0), within(1e-9));
    }

    @Test
    void aRatioSetOnlyForBreakfastStillLandsWhereItStarted() {
        // Lunch, dinner and night have no ratio; the calculator prices breakfast at the breakfast
        // ratio, and the forecast has to agree with that dose.
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 8, 0);
        UserSettingsDTO settings = TestWindowRatios.setAll(settings(null), null);
        settings.setGramsPerUnitBreakfast(6.0);
        double units = CARBS / 6.0;

        double landing = landing(settings, now, units, 0, 480);

        assertThat(landing).as("a meal priced by a window-only ratio must not move the landing")
                .isCloseTo(START, within(0.6));
    }

    @Test
    void aWindowOnlyRatioKeepsTheTwinMealScale() {
        // A twin meal scale of 1.5 on the neutral A_G: the forecast's meal magnitude
        // must be the window ratio's magnitude times that scale, not the scale cancelled out.
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 8, 0);
        UserSettingsDTO settings = settings(null);
        settings.setGramsPerUnitBreakfast(6.0);
        HovorkaParameters twinned = withMealMagnitude(
                1.5 * HovorkaParameterService.mealMagnitude(BASE_ISF, params.vG(), null));

        double magnitude = twinned.aG() * HovorkaGlucosePredictionService.mealWindowFactor(now, twinned, settings);

        assertThat(magnitude).isCloseTo(
                1.5 * HovorkaParameterService.mealMagnitude(twinned.isf(), twinned.vG(), 6.0), within(1e-9));
    }

    @Test
    void theFactorStaysClamped() {
        // A window ISF 4x the base ISF is a settings mistake: the ISF part stops at 3.0. The ratio
        // sizing (10 g/U) is applied on top, unclamped.
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 8, 0);

        assertThat(HovorkaGlucosePredictionService.mealWindowFactor(now, params, settings(4.0)))
                .isCloseTo(HovorkaGlucosePredictionService.MEAL_WINDOW_FACTOR_MAX * defaultRatioMagnitude(), within(1e-9));
    }

    @Test
    void theFactorStaysClampedBelow() {
        // A window ISF of 0.2 against base 1.0: the ISF part stops at 0.33.
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 8, 0);

        assertThat(HovorkaGlucosePredictionService.mealWindowFactor(now, params, settings(0.2)))
                .isCloseTo(HovorkaGlucosePredictionService.MEAL_WINDOW_FACTOR_MIN * defaultRatioMagnitude(), within(1e-9));
    }

    @Test
    void aLowIsfToRatioIsNotClamped() {
        // ISF 1.0 with 15 g/U at 70 kg sizes the meal at the A_G floor 0.3 (4.48 x 1.0 / 15 = 0.299), below
        // the factor bounds' 0.33: only the ISF part is bounded, so the meal keeps the size the old single
        // ratio gave it.
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 8, 0);
        double vG = HovorkaParameters.VG_PER_KG * 70.0;
        HovorkaParameters neutral = new HovorkaParameters(vG, params.f01(), params.egpNet(), params.egp0(),
                params.k12(), params.k21(), params.tMaxG(), HovorkaParameterService.mealMagnitude(1.0, vG, null),
                1.0, 70.0);
        UserSettingsDTO s = new UserSettingsDTO();
        s.setIsf(1.0);
        TestWindowRatios.setAll(s, 10.0);
        s.setGramsPerUnitBreakfast(15.0);

        assertThat(neutral.aG() * HovorkaGlucosePredictionService.mealWindowFactor(now, neutral, s))
                .isCloseTo(HovorkaParameterService.mealMagnitude(1.0, vG, 15.0), within(1e-9));
    }

    @Test
    void anUnusableWindowRatioLeavesOnlyTheIsfPart() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 12, 0);

        assertThat(HovorkaGlucosePredictionService.mealWindowFactor(now, params, settings(1.47, 2.0)))
                .isCloseTo(1.47, within(1e-9));
    }

    @Test
    void aLunchMealIsSizedByTheLunchIsf() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 12, 0);

        assertThat(HovorkaGlucosePredictionService.mealWindowFactor(now, params, settings(1.47)))
                .isCloseTo(1.47 * defaultRatioMagnitude(), within(1e-9));
    }

    @Test
    void illnessDoesNotChangeHowMuchAMealAdds() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 12, 0);
        UserSettingsDTO sick = settings(1.47).withSickProfile(new SickProfile(
                List.of(new SickProfile.Span(now.minusDays(1), null, now, SickSeverity.SEVERE)), null));

        assertThat(HovorkaGlucosePredictionService.mealWindowFactor(now, params, sick))
                .isCloseTo(1.47 * defaultRatioMagnitude(), within(1e-9));
    }

    @Test
    void anUnsetWindowRatioLeavesOnlyTheIsfPart() {
        // No ratio for lunch (the calculator refuses to dose there): the meal keeps the neutral magnitude.
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 12, 0);
        UserSettingsDTO noRatio = TestWindowRatios.setAll(settings(1.47), null);

        assertThat(HovorkaGlucosePredictionService.mealWindowFactor(now, params, noRatio)).isCloseTo(1.47, within(1e-9));
    }

    @Test
    void aBigDoseLongBeforeASlowMealStillDipsLow() {
        // The low check must keep catching real timing dips: the whole dose 60 min before the meal.
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 12, 0);
        double units = CARBS / GRAMS_PER_UNIT;

        List<PredictionPointDTO> curve = curve(settings(1.47), now, units, 60, 480);
        double lowest = curve.stream().mapToDouble(PredictionPointDTO::getPredictedGlucose).min().orElseThrow();

        assertThat(lowest).isLessThan(START - 1.5);
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The 10 g/U magnitude every window carries in {@link #settings(Double)}, over the neutral one. */
    private double defaultRatioMagnitude() {
        return HovorkaParameterService.mealMagnitude(BASE_ISF, params.vG(), GRAMS_PER_UNIT) / params.aG();
    }

    private HovorkaParameters withMealMagnitude(double aG) {
        return new HovorkaParameters(params.vG(), params.f01(), params.egpNet(), params.egp0(), params.k12(),
                params.k21(), params.tMaxG(), aG, params.isf(), params.weightKg());
    }

    /** {@link #settings(Double)} with {@code windowRatio} in every window; null keeps the 10 g/U. */
    private static UserSettingsDTO settings(Double windowIsf, Double windowRatio) {
        UserSettingsDTO s = settings(windowIsf);
        return windowRatio == null ? s : TestWindowRatios.setAll(s, windowRatio);
    }

    /** Base ISF 1.0, {@code windowIsf} in every window, and 10 g/U in every window. */
    private static UserSettingsDTO settings(Double windowIsf) {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setIsf(BASE_ISF);
        TestWindowRatios.setAll(s, GRAMS_PER_UNIT);
        s.setIsfBreakfast(windowIsf);
        s.setIsfLunch(windowIsf);
        s.setIsfDinner(windowIsf);
        s.setIsfNight(windowIsf);
        return s;
    }

    /** The dose at {@code now}, the meal {@code mealDelayMin} later. */
    private List<PredictionPointDTO> curve(UserSettingsDTO settings, LocalDateTime now, double units,
                                           int mealDelayMin, int minutes) {
        lenient().when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settings);
        CarbsEntry meal = CarbsEntry.builder().timestamp(now.plusMinutes(mealDelayMin)).carbs(CARBS).build();
        InsulinDose dose = InsulinDose.builder().timestamp(now).units(units).build();
        return service.buildPredictionPath(params, START, now, List.of(meal), List.of(dose), List.of(),
                USER_ID, minutes);
    }

    private double landing(UserSettingsDTO settings, LocalDateTime now, double units, int mealDelayMin,
                           int minutes) {
        return curve(settings, now, units, mealDelayMin, minutes).stream()
                .filter(pt -> pt.getTimestamp().equals(now.plusMinutes(minutes)))
                .mapToDouble(PredictionPointDTO::getPredictedGlucose).findFirst().orElse(Double.NaN);
    }
}
