package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
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

/** The insulin-to-carb ratio, like the ISF, is the one in force at the bolus's own time of day. */
class InsulinCalculatorServiceRatioWindowTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private UserSettingsDTO settings;
    private InsulinCalculatorService service;

    @BeforeEach
    void setUp() {
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        settings = new UserSettingsDTO(
                UUID.randomUUID(), USER_ID, /*carbRatio*/ 2.0, /*isf*/ 1.5,
                /*halfLife*/ 45, /*maxCob*/ 240);
        settings.setIsfBreakfast(1.5);
        settings.setIsfLunch(1.5);
        settings.setIsfDinner(1.5);
        settings.setIsfNight(1.5);
        settings.setBodyWeightKg(84.0);
        settings.setGramsPerUnit(7.5);
        settings.setGramsPerUnitBreakfast(5.0);
        when(userSettingsService.getUserSettings(any(UUID.class))).thenReturn(settings);
        service = new InsulinCalculatorService(userSettingsService);
    }

    @Test
    void breakfastIsPricedWithTheBreakfastRatio() {
        assertThat(dose("2026-08-21T08:00:00")).isCloseTo(16.0, within(0.01));
    }

    @Test
    void lunchFallsBackToTheSingleRatio() {
        assertThat(dose("2026-08-21T12:00:00")).isCloseTo(10.67, within(0.01));
    }

    @Test
    void elevenOClockIsPricedAsLunch() {
        assertThat(dose("2026-08-21T11:00:00")).isCloseTo(10.67, within(0.01));
    }

    @Test
    void refusesAnOutOfRangeWindowRatio() {
        settings.setGramsPerUnitBreakfast(2.0);
        assertThatThrownBy(() -> dose("2026-08-21T08:00:00"))
                .isInstanceOf(DosingRefusedException.class)
                .hasFieldOrPropertyWithValue("reason", DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT);
        assertThat(dose("2026-08-21T12:00:00")).isCloseTo(10.67, within(0.01));
    }

    @Test
    void windowRatioWorksWithoutASingleRatio() {
        settings.setGramsPerUnit(null);
        assertThat(dose("2026-08-21T08:00:00")).isCloseTo(16.0, within(0.01));
        assertThatThrownBy(() -> dose("2026-08-21T12:00:00"))
                .isInstanceOf(DosingRefusedException.class)
                .hasFieldOrPropertyWithValue("reason", DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT);
    }

    private double dose(String isoTimestamp) {
        InsulinCalculationRequest req = new InsulinCalculationRequest();
        req.setUserId(USER_ID.toString());
        req.setCarbs(80.0);
        req.setCurrentGlucose(6.5);
        req.setTargetGlucose(6.5);
        req.setActiveInsulin(0.0);
        ClientTimeInfo cti = new ClientTimeInfo();
        cti.setTimestamp(isoTimestamp);
        req.setClientTimeInfo(cti);
        return service.calculateRecommendedInsulin(req).getRecommendedInsulin();
    }
}
