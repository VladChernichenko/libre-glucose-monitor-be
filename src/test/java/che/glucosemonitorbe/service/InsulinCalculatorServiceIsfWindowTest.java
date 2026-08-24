package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dosing must price a bolus with the ISF in force at that bolus's own time of day.
 *
 * <p>These are the real stored settings of the 21 Aug incident: carbRatio 2.0, base isf 1.0,
 * isf_dinner 1.5, isf_night 1.0. An 80 g dinner priced on the base ISF yields 16 u; priced on
 * the dinner ISF it yields 10.7 u. The user took 10 u and still finished the evening at
 * 3.8 mmol/L.
 */
class InsulinCalculatorServiceIsfWindowTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private UserSettingsService userSettingsService;
    private InsulinCalculatorService service;

    @BeforeEach
    void setUp() {
        userSettingsService = mock(UserSettingsService.class);
        UserSettingsDTO settings = new UserSettingsDTO(
                UUID.randomUUID(), USER_ID, /*carbRatio*/ 2.0, /*isf*/ 1.0,
                /*halfLife*/ 45, /*maxCob*/ 240);
        settings.setIsfBreakfast(1.5);
        settings.setIsfLunch(1.5);
        settings.setIsfDinner(1.5);
        settings.setIsfNight(1.0);
        settings.setBodyWeightKg(84.0);
        when(userSettingsService.getUserSettings(any(UUID.class))).thenReturn(settings);
        service = new InsulinCalculatorService(userSettingsService);
    }

    /** 18:00 is DINNER: gramsPerUnit = 10 x 1.5 / 2.0 = 7.5, so 80 g -> 10.67 u. */
    @Test
    void mealDoseUsesTheDinnerIsfForADinnerTimeRequest() {
        InsulinCalculationResponse r = service.calculateRecommendedInsulin(
                request(80.0, 6.5, 6.5, "2026-08-21T18:00:00"));
        assertThat(r.getRecommendedInsulin()).isCloseTo(10.67, within(0.01));
    }

    /**
     * 23:00 is NIGHT, where the override equals the base: gramsPerUnit = 5.0, so 80 g -> 16 u.
     * Without this the dinner assertion alone would also pass an implementation that had merely
     * been rebased onto a different single constant.
     */
    @Test
    void mealDoseUsesTheNightIsfForALateRequest() {
        InsulinCalculationResponse r = service.calculateRecommendedInsulin(
                request(80.0, 6.5, 6.5, "2026-08-21T23:00:00"));
        assertThat(r.getRecommendedInsulin()).isCloseTo(16.0, within(0.01));
    }

    /** Correction at the 20:07 glucose of the incident: (14.1 - 6.5) / 1.5 = 5.07 u. */
    @Test
    void correctionDoseUsesTheDinnerIsf() {
        InsulinCalculationResponse r = service.calculateRecommendedInsulin(
                request(0.0, 14.1, 6.5, "2026-08-21T20:07:00"));
        assertThat(r.getRecommendedInsulin()).isCloseTo(5.07, within(0.01));
    }

    /** Same correction at night, where ISF is 1.0: (14.1 - 6.5) / 1.0 = 7.6 u. */
    @Test
    void correctionDoseUsesTheNightIsf() {
        InsulinCalculationResponse r = service.calculateRecommendedInsulin(
                request(0.0, 14.1, 6.5, "2026-08-21T23:30:00"));
        assertThat(r.getRecommendedInsulin()).isCloseTo(7.6, within(0.01));
    }

    /** No window override and no base ISF: refuse, never guess. */
    @Test
    void refusesWhenNeitherWindowNorBaseIsfIsSet() {
        UserSettingsDTO bare = new UserSettingsDTO(
                UUID.randomUUID(), USER_ID, /*carbRatio*/ 2.0, /*isf*/ null,
                /*halfLife*/ 45, /*maxCob*/ 240);
        when(userSettingsService.getUserSettings(any(UUID.class))).thenReturn(bare);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(
                request(80.0, 6.5, 6.5, "2026-08-21T18:00:00")))
                .isInstanceOf(DosingRefusedException.class)
                .hasFieldOrPropertyWithValue("reason", DosingRefusalReason.SETTINGS_INVALID);
    }

    /** A missing clientTimeInfo must not throw - it falls back to the server clock. */
    @Test
    void missingClientTimeFallsBackToNowWithoutThrowing() {
        InsulinCalculationRequest req = request(80.0, 6.5, 6.5, null);
        req.setClientTimeInfo(null);
        assertThat(service.calculateRecommendedInsulin(req).getRecommendedInsulin())
                .isGreaterThan(0.0);
    }

    private InsulinCalculationRequest request(double carbs, double current, double target,
                                              String isoTimestamp) {
        InsulinCalculationRequest req = new InsulinCalculationRequest();
        req.setUserId(USER_ID.toString());
        req.setCarbs(carbs);
        req.setCurrentGlucose(current);
        req.setTargetGlucose(target);
        req.setActiveInsulin(0.0);
        if (isoTimestamp != null) {
            ClientTimeInfo cti = new ClientTimeInfo();
            cti.setTimestamp(isoTimestamp);
            req.setClientTimeInfo(cti);
        }
        return req;
    }
}
