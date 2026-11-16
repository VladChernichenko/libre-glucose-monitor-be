package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.sick.SickProfile;
import che.glucosemonitorbe.sick.SickProfile.Span;
import che.glucosemonitorbe.sick.SickSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/** The forecast while the user is sick: doses keep the ISF of their moment; basal need rises. */
@ExtendWith(MockitoExtension.class)
class HovorkaSickModeTest {

    @Mock HovorkaParameterService paramService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock UserSettingsService userSettingsService;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 1, 10, 0);
    private static final double ISF = 2.2;

    private HovorkaGlucosePredictionService service;
    private HovorkaParameters params;

    @BeforeEach
    void setUp() {
        DallaManGutModel gutModel = new DallaManGutModel();
        service = new HovorkaGlucosePredictionService(
                paramService, new HovorkaOdeSolver(gutModel), new BasalInsulinResolver(),
                insulinPrefsService, gutModel, userSettingsService, PredictionResidualProvider.NONE);

        double weight = 70.0;
        double f01 = HovorkaParameters.F01_PER_KG * weight;
        params = new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * weight, f01, f01, HovorkaParameters.EGP0_PER_KG * weight,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 0.80, ISF, weight);
        lenient().when(insulinPrefsService.getRapidIobParameters(any()))
                .thenReturn(new RapidInsulinIobParameters(4.5, 55.0));
    }

    // ---- doses ---------------------------------------------------------------------------

    @Test
    void aDoseTakenWhileSickLowersGlucoseLess() {
        // The dose's action is complete by 6 h (DIA 4.5 h), so the landing at 360 min carries its whole effect.
        double g0 = 10.0;
        double units = 2.0;
        InsulinDose dose = InsulinDose.builder().timestamp(NOW).units(units).build();
        SickProfile moderate = profile(null, span(SickSeverity.MODERATE, NOW.minusDays(1), null));

        double healthy = landing(settings(SickProfile.EMPTY), g0, List.of(), List.of(dose), List.of(), 360);
        double sick = landing(settings(moderate), g0, List.of(), List.of(dose), List.of(), 360);

        double expectedGap = ISF * units * (1 - 0.70);
        assertThat(sick).isGreaterThan(healthy);
        assertThat(sick - healthy).isCloseTo(expectedGap, within(0.25 * expectedGap));
    }

    @Test
    void aDoseTakenBeforeAStepUpKeepsItsOwnMultiplier() {
        InsulinDose dose = InsulinDose.builder().timestamp(NOW.minusMinutes(60)).units(3.0).build();
        SickProfile moderateOnly = profile(null, span(SickSeverity.MODERATE, NOW.minusDays(1), null));
        SickProfile steppedUp = profile(null,
                span(SickSeverity.MODERATE, NOW.minusDays(1), NOW.minusMinutes(20)),
                span(SickSeverity.SEVERE, NOW.minusMinutes(20), null));

        List<PredictionPointDTO> a = curve(settings(moderateOnly), 10.0, List.of(), List.of(dose), List.of(), 240);
        List<PredictionPointDTO> b = curve(settings(steppedUp), 10.0, List.of(), List.of(dose), List.of(), 240);

        assertThat(b).hasSameSizeAs(a);
        for (int i = 0; i < a.size(); i++) {
            assertThat(b.get(i).getPredictedGlucose()).isCloseTo(a.get(i).getPredictedGlucose(), within(0.01));
        }
    }

    @Test
    void carbRiseIsUnchangedBySickMode() {
        CarbsEntry meal = CarbsEntry.builder().timestamp(NOW).carbs(40.0).build();
        SickProfile severe = profile(null, span(SickSeverity.SEVERE, NOW.minusDays(1), null));

        List<PredictionPointDTO> healthy = curve(settings(SickProfile.EMPTY), 7.0, List.of(meal), List.of(), List.of(), 240);
        List<PredictionPointDTO> sick = curve(settings(severe), 7.0, List.of(meal), List.of(), List.of(), 240);

        assertThat(sick).hasSameSizeAs(healthy);
        for (int i = 0; i < healthy.size(); i++) {
            assertThat(sick.get(i).getPredictedGlucose())
                    .isCloseTo(healthy.get(i).getPredictedGlucose(), within(0.05));
        }
    }

    // ---- basal ---------------------------------------------------------------------------

    @Test
    void anUnchangedLongActingDoseDriftsUpWhileSick() {
        Note basal = longActing(20.0, 5);
        double healthy = landing(settings(SickProfile.EMPTY), 6.1, List.of(), List.of(), List.of(basal), 240);
        double sick = landing(settings(moderateWithHealthyBasal(20.0)), 6.1, List.of(), List.of(), List.of(basal), 240);

        assertThat(sick).isGreaterThan(healthy);
    }

    @Test
    void aRaisedLongActingDoseDoesNotDrift() {
        // 20 U is the healthy dose; +15% at MODERATE is 23 U, which the user logged.
        Note raised = longActing(23.0, 5);
        Note healthyDose = longActing(20.0, 5);
        double healthy = landing(settings(SickProfile.EMPTY), 6.1, List.of(), List.of(), List.of(healthyDose), 240);
        double sick = landing(settings(moderateWithHealthyBasal(20.0)), 6.1, List.of(), List.of(), List.of(raised), 240);

        assertThat(sick).isCloseTo(healthy, within(0.1));
    }

    @Test
    void withoutALongActingNoteThereIsNoDrift() {
        double healthy = landing(settings(SickProfile.EMPTY), 6.1, List.of(), List.of(), List.of(), 240);
        double sick = landing(settings(moderateWithHealthyBasal(20.0)), 6.1, List.of(), List.of(), List.of(), 240);

        assertThat(sick).isCloseTo(healthy, within(0.01));
    }

    // ---- helpers -------------------------------------------------------------------------

    private SickProfile moderateWithHealthyBasal(double healthyUnits) {
        return profile(healthyUnits, span(SickSeverity.MODERATE, NOW.minusDays(1), null));
    }

    private static Span span(SickSeverity severity, LocalDateTime from, LocalDateTime endedAt) {
        return new Span(from, endedAt, endedAt != null ? endedAt : NOW, severity);
    }

    private static SickProfile profile(Double healthyBasal, Span... spans) {
        return new SickProfile(List.of(spans), healthyBasal);
    }

    private static UserSettingsDTO settings(SickProfile profile) {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setIsf(ISF);
        return s.withSickProfile(profile);
    }

    private static Note longActing(double units, int hoursAgo) {
        Note n = new Note();
        n.setUserId(USER_ID);
        n.setType(Note.TYPE_LONG_ACTING);
        n.setMeal("Long-acting");
        n.setInsulin(units);
        n.setTimestamp(NOW.minusHours(hoursAgo));
        return n;
    }

    private List<PredictionPointDTO> curve(UserSettingsDTO settings, double g0, List<CarbsEntry> carbs,
                                           List<InsulinDose> doses, List<Note> longActing, int minutes) {
        lenient().when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settings);
        return service.buildPredictionPath(params, g0, NOW, carbs, doses, longActing, USER_ID, minutes);
    }

    private double landing(UserSettingsDTO settings, double g0, List<CarbsEntry> carbs,
                           List<InsulinDose> doses, List<Note> longActing, int minutes) {
        List<PredictionPointDTO> c = curve(settings, g0, carbs, doses, longActing, minutes);
        return c.stream().filter(pt -> pt.getTimestamp().equals(NOW.plusMinutes(minutes)))
                .mapToDouble(PredictionPointDTO::getPredictedGlucose).findFirst().orElse(Double.NaN);
    }
}
