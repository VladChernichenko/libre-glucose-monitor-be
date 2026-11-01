package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsulinCalculatorForecastCorrectionTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String USER = USER_ID.toString();

    private InsulinCalculatorService calc;

    @BeforeEach
    void setUp() {
        UserSettingsService settingsService = mock(UserSettingsService.class);
        UserSettingsDTO settings = new UserSettingsDTO(
                UUID.randomUUID(), USER_ID, /*carbRatio*/ 1.0, /*isf*/ 2.0, /*halfLife*/ 45, /*maxCob*/ 240);
        settings.setBodyWeightKg(70.0); // max bolus 0.3 U/kg -> 21 U
        when(settingsService.getUserSettings(any(UUID.class))).thenReturn(settings);
        calc = new InsulinCalculatorService(settingsService);
    }

    @Test
    void aimsFromLandingNotCurrent() {
        assertEquals(2.75, calc.calculateForecastCorrection(USER, 9.0, 11.0, 5.5, 2.0), 1e-9);
    }

    @Test
    void refusesBelow39() {
        assertRefused(DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD,
                () -> calc.calculateForecastCorrection(USER, 3.8, 11.0, 5.5, 2.0));
    }

    @Test
    void refusesNonPositiveIsf() {
        assertRefused(DosingRefusalReason.SETTINGS_INVALID,
                () -> calc.calculateForecastCorrection(USER, 9.0, 11.0, 5.5, 0.0));
    }

    @Test
    void refusesNonFiniteIsf() {
        assertRefused(DosingRefusalReason.SETTINGS_INVALID,
                () -> calc.calculateForecastCorrection(USER, 9.0, 11.0, 5.5, Double.NaN));
    }

    @Test
    void refusesOverMaxBolus() {
        assertRefused(DosingRefusalReason.DOSE_EXCEEDS_MAX_BOLUS,
                () -> calc.calculateForecastCorrection(USER, 9.0, 30.0, 5.5, 1.0));
    }

    @Test
    void landingBelowTarget_isZero() {
        assertEquals(0.0, calc.calculateForecastCorrection(USER, 9.0, 5.0, 5.5, 2.0), 1e-9);
    }

    private static void assertRefused(DosingRefusalReason expected, Executable call) {
        DosingRefusedException ex = assertThrows(DosingRefusedException.class, call);
        assertEquals(expected, ex.getReason());
    }
}
