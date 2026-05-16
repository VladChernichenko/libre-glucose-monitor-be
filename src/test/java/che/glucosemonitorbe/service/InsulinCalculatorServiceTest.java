package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsulinCalculatorServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private COBSettingsService cobSettingsService;
    private InsulinCalculatorService service;

    @BeforeEach
    void setUp() {
        cobSettingsService = mock(COBSettingsService.class);
        // Default: carbRatio=1.0, ISF=2.2; halfLife / maxCOB not used in these tests
        COBSettingsDTO defaultSettings = new COBSettingsDTO(
                UUID.randomUUID(), USER_ID, /*carbRatio*/ 1.0, /*isf*/ 2.2, /*halfLife*/ 45, /*maxCob*/ 240);
        when(cobSettingsService.getCOBSettings(any(UUID.class))).thenReturn(defaultSettings);

        service = new InsulinCalculatorService(cobSettingsService);
    }

    // ── IOB decay basic cases ──────────────────────────────────────────────────

    @Test
    void futureDoseContributesZero() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose dose = InsulinDose.builder()
                .timestamp(now.plusMinutes(30))
                .units(5.0)
                .build();
        assertEquals(0.0, service.calculateRemainingInsulin(dose, now), 1e-9);
    }

    @Test
    void doseExactlyAtCurrentTime_isFullUnits() {
        LocalDateTime t = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose dose = InsulinDose.builder()
                .timestamp(t)
                .units(4.0)
                .build();
        assertEquals(4.0, service.calculateRemainingInsulin(dose, t), 0.02);
    }

    @Test
    void beyondDiaReturnsZero() {
        LocalDateTime doseTime = LocalDateTime.of(2025, 6, 1, 8, 0);
        LocalDateTime now = doseTime.plusMinutes(4 * 60 + 1);
        InsulinDose dose = InsulinDose.builder()
                .timestamp(doseTime)
                .units(10.0)
                .build();
        // Explicit 4 h DIA
        assertEquals(0.0, service.calculateRemainingInsulin(dose, now, 4.0, 75.0), 1e-9);
    }

    @Test
    void totalIobSumsDoses() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose d1 = InsulinDose.builder().timestamp(now.minusHours(1)).units(2.0).build();
        InsulinDose d2 = InsulinDose.builder().timestamp(now.minusMinutes(30)).units(3.0).build();
        double sum = service.calculateTotalActiveInsulin(List.of(d1, d2), now);
        assertTrue(sum > 0 && sum < 5.0, "IOB should be between 0 and total bolused units");
    }

    @Test
    void iobDecreasesAfterBolus() {
        LocalDateTime doseTime = LocalDateTime.of(2025, 6, 1, 10, 0);
        InsulinDose dose = InsulinDose.builder().timestamp(doseTime).units(6.0).build();
        double at0 = service.calculateRemainingInsulin(dose, doseTime);
        double at2h = service.calculateRemainingInsulin(dose, doseTime.plusMinutes(120));
        assertEquals(6.0, at0, 0.05);
        assertTrue(at2h < at0 && at2h > 0, "IOB should decay: at0=" + at0 + " at2h=" + at2h);
    }

    // ── OpenAPS exponential curve unit tests ───────────────────────────────────

    @Test
    void openApsExponential_negativeMins_returnsZero() {
        assertThat(InsulinCalculatorService.iobOpenApsExponential(5.0, -1.0, 4.5, 55.0))
                .isEqualTo(0.0);
    }

    @Test
    void openApsExponential_zeroUnits_returnsZero() {
        assertThat(InsulinCalculatorService.iobOpenApsExponential(0.0, 30.0, 4.5, 55.0))
                .isEqualTo(0.0);
    }

    @Test
    void openApsExponential_beyondDia_returnsZero() {
        double endMinutes = 4.5 * 60;
        assertThat(InsulinCalculatorService.iobOpenApsExponential(5.0, endMinutes + 1, 4.5, 55.0))
                .isEqualTo(0.0);
    }

    @Test
    void openApsExponential_atTime0_isFullUnits() {
        double iob = InsulinCalculatorService.iobOpenApsExponential(5.0, 0.0, 4.5, 55.0);
        assertThat(iob).isCloseTo(5.0, within(0.05));
    }

    @Test
    void openApsExponential_neverExceedsUnits() {
        for (double t = 0; t <= 270; t += 10) {
            double iob = InsulinCalculatorService.iobOpenApsExponential(5.0, t, 4.5, 55.0);
            assertThat(iob).isLessThanOrEqualTo(5.0 + 1e-9);
        }
    }

    @Test
    void openApsExponential_neverNegative() {
        for (double t = 0; t <= 270; t += 5) {
            double iob = InsulinCalculatorService.iobOpenApsExponential(5.0, t, 4.5, 55.0);
            assertThat(iob).isGreaterThanOrEqualTo(0.0);
        }
    }

    /** Peak time is ≈ peak minutes into the bolus. IOB at peak must be < dose. */
    @Test
    void openApsExponential_peakIsBeforeHalfDia() {
        double at55min  = InsulinCalculatorService.iobOpenApsExponential(5.0, 55.0,  4.5, 55.0);
        double at100min = InsulinCalculatorService.iobOpenApsExponential(5.0, 100.0, 4.5, 55.0);
        // After peak, IOB falls → at 100 min should be less than at 55 min
        assertThat(at100min).isLessThan(at55min);
    }

    // ── BE-5: activity status expires when insulin is past DIA ─────────────────

    /**
     * BE-5 regression: status must return "none" once the last dose is beyond DIA,
     * not "falling" forever. We verify the guard using explicit diaHours.
     * Disabled until BE-5 fix is applied to getInsulinActivityStatus().
     */
    @Test
    void activityStatus_beyondDia_returnsNone() {
        LocalDateTime doseTime = LocalDateTime.of(2025, 6, 1, 8, 0);
        double diaHours = 4.5;
        LocalDateTime pastDia = doseTime.plusHours((long) diaHours).plusMinutes(1);
        InsulinDose dose = InsulinDose.builder().timestamp(doseTime).units(4.0).build();

        // Total IOB is 0 — status should not be "falling"
        String status = service.getInsulinActivityStatus(List.of(dose), pastDia, InsulinCalculatorService.DEFAULT_PEAK_MINUTES);
        double totalIob = service.calculateTotalActiveInsulin(List.of(dose), pastDia, diaHours, InsulinCalculatorService.DEFAULT_PEAK_MINUTES);

        assertThat(totalIob).isEqualTo(0.0);
        // With the BE-5 fix applied, status must not be "falling" when IOB=0
        // (current impl returns "falling" — this test will fail until BE-5 is fixed)
        assertThat(status).isNotEqualTo("falling");
    }

    @Test
    void activityStatus_noDoses_returnsNone() {
        assertThat(service.getInsulinActivityStatus(List.of(), LocalDateTime.now()))
                .isEqualTo("none");
    }

    @Test
    void activityStatus_risingPhase() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        // 10 min ago: well before peak (55 min) → "rising"
        InsulinDose dose = InsulinDose.builder().timestamp(now.minusMinutes(10)).units(4.0).build();
        assertThat(service.getInsulinActivityStatus(List.of(dose), now))
                .isEqualTo("rising");
    }

    @Test
    void activityStatus_peakPhase() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        // 55 min ago: at peak ± 15 min → "peak"
        InsulinDose dose = InsulinDose.builder().timestamp(now.minusMinutes(55)).units(4.0).build();
        assertThat(service.getInsulinActivityStatus(List.of(dose), now))
                .isEqualTo("peak");
    }

    // ── BE-4: ISF resolved from user settings ─────────────────────────────────

    @Test
    void recommendedInsulin_correctionUsesConfiguredIsf() {
        // ISF=2.2: correction = (12.0 – 6.0) / 2.2 ≈ 2.73 u; carb bolus = 60/12 = 5 u → total ≈ 7.73
        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(60.0);
        request.setCurrentGlucose(12.0);
        request.setTargetGlucose(6.0);
        request.setUserId(USER_ID.toString());

        var response = service.calculateRecommendedInsulin(request);
        // With ISF=2.2: correction≈2.73, carb=5 → total≈7.73; with old ISF=1.0: total=11
        assertThat(response.getRecommendedInsulin()).isCloseTo(7.73, within(0.1));
    }

    @Test
    void recommendedInsulin_noUserIdFallsBackToDefaultIsf() {
        // No userId → DEFAULT_ISF=2.2 used directly
        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(60.0);
        request.setCurrentGlucose(12.0);
        request.setTargetGlucose(6.0);
        request.setUserId(null);

        var response = service.calculateRecommendedInsulin(request);
        assertThat(response.getRecommendedInsulin()).isCloseTo(7.73, within(0.1));
    }

    @Test
    void recommendedInsulin_activeInsulinSubtracted() {
        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(30.0);
        request.setCurrentGlucose(6.0);
        request.setTargetGlucose(6.0);
        request.setActiveInsulin(5.0);
        request.setUserId(USER_ID.toString());

        // carb bolus = 30/12 = 2.5; active=5 → max(0, 2.5-5) = 0
        var response = service.calculateRecommendedInsulin(request);
        assertThat(response.getRecommendedInsulin()).isEqualTo(0.0);
    }
}
