package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
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
 * mmol/L low, and the meal card withheld the dose as FORECAST_LOW.
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
        double aG = HovorkaParameterService.mealMagnitude(BASE_ISF, vG, GRAMS_PER_UNIT);
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

    @Test
    void aLunchMealIsSizedByTheLunchIsf() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 12, 0);

        assertThat(HovorkaGlucosePredictionService.mealWindowFactor(now, params, settings(1.47)))
                .isCloseTo(1.47, within(1e-9));
    }

    @Test
    void illnessDoesNotChangeHowMuchAMealAdds() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 12, 0);
        UserSettingsDTO sick = settings(1.47).withSickProfile(new SickProfile(
                List.of(new SickProfile.Span(now.minusDays(1), null, now, SickSeverity.SEVERE)), null));

        assertThat(HovorkaGlucosePredictionService.mealWindowFactor(now, params, sick))
                .isCloseTo(1.47, within(1e-9));
    }

    @Test
    void withoutAUsableRatioMealsAreNotRescaled() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 12, 0);
        UserSettingsDTO noRatio = settings(1.47);
        noRatio.setGramsPerUnit(null);

        assertThat(HovorkaGlucosePredictionService.mealWindowFactor(now, params, noRatio)).isEqualTo(1.0);
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

    private static UserSettingsDTO settings(Double windowIsf) {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setIsf(BASE_ISF);
        s.setGramsPerUnit(GRAMS_PER_UNIT);
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
