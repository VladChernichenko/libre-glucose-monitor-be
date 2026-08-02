package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.ActiveInsulinResponse;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsulinCalculatorServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private UserSettingsService userSettingsService;
    private InsulinCalculatorService service;

    @BeforeEach
    void setUp() {
        userSettingsService = mock(UserSettingsService.class);
        // Default: carbRatio=1.0, ISF=2.2; halfLife / maxCOB not used in these tests
        UserSettingsDTO defaultSettings = new UserSettingsDTO(
                UUID.randomUUID(), USER_ID, /*carbRatio*/ 1.0, /*isf*/ 2.2, /*halfLife*/ 45, /*maxCob*/ 240);
        when(userSettingsService.getUserSettings(any(UUID.class))).thenReturn(defaultSettings);

        service = new InsulinCalculatorService(userSettingsService);
    }

    // -- IOB decay basic cases --------------------------------------------------

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

    // -- OpenAPS exponential curve unit tests -----------------------------------

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
        // After peak, IOB falls -> at 100 min should be less than at 55 min
        assertThat(at100min).isLessThan(at55min);
    }

    // -- BE-5: activity status expires when insulin is past DIA -----------------

    /**
     * BE-5 regression: status must return "none" once the last dose is beyond DIA,
     * not "falling" forever. We verify the guard using explicit diaHours.
     * Disabled until BE-5 fix is applied to getInsulinActivityStatus().
     */
    @Test
    void activityStatus_beyondDia_returnsNone() {
        LocalDateTime doseTime = LocalDateTime.of(2025, 6, 1, 8, 0);
        double diaHours = 4.5;
        LocalDateTime pastDia = doseTime.plusMinutes((long)(diaHours * 60) + 1);
        InsulinDose dose = InsulinDose.builder().timestamp(doseTime).units(4.0).build();

        // Total IOB is 0 - status should not be "falling"
        String status = service.getInsulinActivityStatus(List.of(dose), pastDia, InsulinCalculatorService.DEFAULT_PEAK_MINUTES);
        double totalIob = service.calculateTotalActiveInsulin(List.of(dose), pastDia, diaHours, InsulinCalculatorService.DEFAULT_PEAK_MINUTES);

        assertThat(totalIob).isEqualTo(0.0);
        // With the BE-5 fix applied, status must not be "falling" when IOB=0
        // (current impl returns "falling" - this test will fail until BE-5 is fixed)
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
        // 10 min ago: well before peak (55 min) -> "rising"
        InsulinDose dose = InsulinDose.builder().timestamp(now.minusMinutes(10)).units(4.0).build();
        assertThat(service.getInsulinActivityStatus(List.of(dose), now))
                .isEqualTo("rising");
    }

    @Test
    void activityStatus_peakPhase() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        // 55 min ago: at peak ± 15 min -> "peak"
        InsulinDose dose = InsulinDose.builder().timestamp(now.minusMinutes(55)).units(4.0).build();
        assertThat(service.getInsulinActivityStatus(List.of(dose), now))
                .isEqualTo("peak");
    }

    // -- BE-4: ISF resolved from user settings ---------------------------------

    @Test
    void recommendedInsulin_correctionUsesConfiguredIsf() {
        // C1: carbRatio=1.0, ISF=2.2 -> 10*2.2/1.0 = 22 g/U.
        // carb bolus = 60/22 ≈ 2.73 u; correction = (12.0 - 6.0) / 2.2 ≈ 2.73 u -> total ≈ 5.45.
        // The old hardcoded 12 g/U gave 7.73 for the same user.
        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(60.0);
        request.setCurrentGlucose(12.0);
        request.setTargetGlucose(6.0);
        request.setUserId(USER_ID.toString());

        var response = service.calculateRecommendedInsulin(request);
        assertThat(response.getRecommendedInsulin()).isCloseTo(5.45, within(0.01));
    }

    @Test
    void recommendedInsulin_noUserId_refuses() {
        // C1/H4: dosing never substitutes a population default for a missing user.
        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(60.0);
        request.setCurrentGlucose(12.0);
        request.setTargetGlucose(6.0);
        request.setUserId(null);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(request))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.SETTINGS_INVALID);
    }

    // -- NotebookLM scenario 3: DIA edge cases --------------------------------

    @Test
    void diaZero_failSafe_returnsZeroNotDivisionByZero() {
        LocalDateTime t = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose dose = InsulinDose.builder().timestamp(t).units(4.0).build();
        // diaHours=0 is guarded by the `if (diaHours <= 0)` check -> must return 0, not throw
        assertEquals(0.0, service.calculateRemainingInsulin(dose, t, 0.0, 55.0), 1e-9);
    }

    @Test
    void peakMinutesZero_failSafe_returnsZero() {
        LocalDateTime t = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose dose = InsulinDose.builder().timestamp(t).units(4.0).build();
        assertEquals(0.0, service.calculateRemainingInsulin(dose, t, 4.5, 0.0), 1e-9);
    }

    @Test
    void diaExactBoundary_atEndMinusOne_nonZero() {
        // t = DIA*60 - 1 (still within window)
        LocalDateTime doseTime = LocalDateTime.of(2025, 6, 1, 8, 0);
        double diaHours = 5.0;
        LocalDateTime almostExpired = doseTime.plusMinutes((long)(diaHours * 60) - 1);
        InsulinDose dose = InsulinDose.builder().timestamp(doseTime).units(4.0).build();
        assertThat(service.calculateRemainingInsulin(dose, almostExpired, diaHours, 75.0))
                .isGreaterThan(0.0);
    }

    @Test
    void diaExactBoundary_atEndMinutes_returnsZero() {
        // t = DIA*60 (exactly at boundary -> 0)
        LocalDateTime doseTime = LocalDateTime.of(2025, 6, 1, 8, 0);
        double diaHours = 5.0;
        LocalDateTime atExpiry = doseTime.plusMinutes((long)(diaHours * 60));
        InsulinDose dose = InsulinDose.builder().timestamp(doseTime).units(4.0).build();
        assertEquals(0.0, service.calculateRemainingInsulin(dose, atExpiry, diaHours, 75.0), 1e-9);
    }

    @Test
    void openApsExponential_nearZeroDenominator_fallsBackToLinear() {
        // denom = 1 - 2*peak/end; when peak = end/2, denom ≈ 0 -> linear fallback
        double diaHours = 4.0;
        double endMin = diaHours * 60.0;          // 240
        double peakMin = endMin / 2.0;             // 120 -> denom = 0
        double units = 5.0;
        double t = 60.0;

        double iob = InsulinCalculatorService.iobOpenApsExponential(units, t, diaHours, peakMin);
        // Linear fallback: units * max(0, 1 - t/end) = 5 * (1 - 60/240) = 5 * 0.75 = 3.75
        assertThat(iob).isCloseTo(3.75, within(0.05));
        assertThat(iob).isBetween(0.0, units);
    }

    @Test
    void openApsExponential_nanGuard_neverReturnsNaN() {
        // Pathological inputs that could produce NaN - must always get a finite value
        for (double peak : new double[]{0.001, 135.0, 269.9}) {
            double iob = InsulinCalculatorService.iobOpenApsExponential(5.0, 60.0, 4.5, peak);
            assertThat(Double.isNaN(iob)).isFalse();
            assertThat(Double.isInfinite(iob)).isFalse();
            assertThat(iob).isBetween(0.0, 5.0 + 1e-9);
        }
    }

    @Test
    void recommendedInsulin_activeInsulinSubtracted() {
        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(30.0);
        request.setCurrentGlucose(6.0);
        request.setTargetGlucose(6.0);
        request.setActiveInsulin(5.0);
        request.setUserId(USER_ID.toString());

        // carb bolus = 30/22 ≈ 1.36; active=5 -> max(0, 1.36-5) = 0
        var response = service.calculateRecommendedInsulin(request);
        assertThat(response.getRecommendedInsulin()).isEqualTo(0.0);
    }

    // -- calculateRemainingInsulin guard branches ------------------------------

    @Test
    void calculateRemainingInsulin_zeroUnits_returnsZero() {
        LocalDateTime t = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose dose = InsulinDose.builder().timestamp(t).units(0.0).build();
        assertEquals(0.0, service.calculateRemainingInsulin(dose, t), 1e-9);
    }

    @Test
    void calculateRemainingInsulin_nullDoseTimestamp_returnsZero() {
        InsulinDose dose = InsulinDose.builder().timestamp(null).units(5.0).build();
        assertEquals(0.0, service.calculateRemainingInsulin(dose, LocalDateTime.now()), 1e-9);
    }

    @Test
    void calculateRemainingInsulin_nullCurrentTime_returnsZero() {
        InsulinDose dose = InsulinDose.builder().timestamp(LocalDateTime.now()).units(5.0).build();
        assertEquals(0.0, service.calculateRemainingInsulin(dose, null), 1e-9);
    }

    // -- getInsulinActivityTimeline (real-life 5u Lunch bolus) -----------------

    @Test
    void activityTimeline_realLifeDose_coversFullDurationWithPeak() {
        // Real-life: 5.0u Lunch bolus from exported notes data
        LocalDateTime doseTime = LocalDateTime.of(2026, 6, 9, 13, 0);
        InsulinDose dose = InsulinDose.builder().timestamp(doseTime).units(5.0).build();

        List<ActiveInsulinResponse> timeline = service.getInsulinActivityTimeline(dose, 4.5);

        // 0..4.5h in 0.25h steps, inclusive -> 19 points
        assertThat(timeline).hasSize(19);

        ActiveInsulinResponse first = timeline.get(0);
        assertThat(first.getTimestamp()).isEqualTo(doseTime);
        assertThat(first.getRemainingUnits()).isCloseTo(5.0, within(0.05));
        assertThat(first.getPercentageRemaining()).isCloseTo(100.0, within(0.5));

        // 4.5h ≈ DIA -> IOB ≈ 0
        ActiveInsulinResponse last = timeline.get(timeline.size() - 1);
        assertThat(last.getRemainingUnits()).isCloseTo(0.0, within(0.5));

        // Somewhere near the 55-min peak the isPeak flag must fire
        assertThat(timeline.stream().anyMatch(p -> Boolean.TRUE.equals(p.getIsPeak()))).isTrue();

        for (ActiveInsulinResponse p : timeline) {
            assertThat(p.getRemainingUnits()).isBetween(0.0, 5.0 + 1e-6);
            assertThat(p.getPercentageRemaining()).isBetween(0.0, 100.0 + 1e-6);
        }
    }

    // -- getInsulinActivityStatus: future dose & falling phase -----------------

    @Test
    void activityStatus_futureDose_returnsNone() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose dose = InsulinDose.builder().timestamp(now.plusMinutes(5)).units(4.0).build();
        assertThat(service.getInsulinActivityStatus(List.of(dose), now)).isEqualTo("none");
    }

    @Test
    void activityStatus_fallingPhase() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        // 100 min ago: past peak+15 (70 min) but well before DIA (270 min) -> "falling"
        InsulinDose dose = InsulinDose.builder().timestamp(now.minusMinutes(100)).units(4.0).build();
        assertThat(service.getInsulinActivityStatus(List.of(dose), now)).isEqualTo("falling");
    }

    // -- getInsulinActivityDescription ------------------------------------------

    @Test
    void activityDescription_noDoses_returnsNoActiveInsulin() {
        assertThat(service.getInsulinActivityDescription(List.of(), LocalDateTime.now()))
                .isEqualTo("No active insulin");
    }

    @Test
    void activityDescription_beyondDia_returnsNoActiveInsulin() {
        LocalDateTime doseTime = LocalDateTime.of(2025, 6, 1, 8, 0);
        LocalDateTime pastDia = doseTime.plusMinutes((long) (InsulinCalculatorService.DEFAULT_DIA_HOURS * 60) + 1);
        InsulinDose dose = InsulinDose.builder().timestamp(doseTime).units(4.0).build();
        assertThat(service.getInsulinActivityDescription(List.of(dose), pastDia))
                .isEqualTo("No active insulin");
    }

    @Test
    void activityDescription_risingPhase_formatsActiveUnits() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        // Real-life: 4.0u Lunch bolus, 10 min ago -> rising
        InsulinDose dose = InsulinDose.builder().timestamp(now.minusMinutes(10)).units(4.0).build();
        assertThat(service.getInsulinActivityDescription(List.of(dose), now))
                .startsWith("Insulin rising - ")
                .endsWith("u active");
    }

    @Test
    void activityDescription_peakPhase_formatsActiveUnits() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        // Real-life: 5.0u Lunch bolus, 55 min ago -> peak
        InsulinDose dose = InsulinDose.builder().timestamp(now.minusMinutes(55)).units(5.0).build();
        assertThat(service.getInsulinActivityDescription(List.of(dose), now))
                .startsWith("Insulin at peak - ")
                .endsWith("u active");
    }

    @Test
    void activityDescription_fallingPhase_formatsActiveUnits() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose dose = InsulinDose.builder().timestamp(now.minusMinutes(100)).units(5.0).build();
        assertThat(service.getInsulinActivityDescription(List.of(dose), now))
                .startsWith("Insulin falling - ")
                .endsWith("u active");
    }

    // -- resolveIsf fallback paths (malformed/missing user settings) ----------

    @Test
    void recommendedInsulin_malformedUserId_refuses() {
        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(60.0);
        request.setCurrentGlucose(12.0);
        request.setTargetGlucose(6.0);
        request.setUserId("not-a-uuid");

        // C1/H4: a userId that cannot be resolved is a reason to stop, not to guess an ISF.
        assertThatThrownBy(() -> service.calculateRecommendedInsulin(request))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.SETTINGS_INVALID);
    }

    @Test
    void recommendedInsulin_settingsWithNullIsf_refuses() {
        UUID userId = UUID.randomUUID();
        when(userSettingsService.getUserSettings(userId))
                .thenReturn(new UserSettingsDTO(UUID.randomUUID(), userId, 1.0, null, 45, 240));

        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(60.0);
        request.setCurrentGlucose(12.0);
        request.setTargetGlucose(6.0);
        request.setUserId(userId.toString());

        // C1/H4: an unconfigured ISF must block the calculation, not default to 2.2.
        assertThatThrownBy(() -> service.calculateRecommendedInsulin(request))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.SETTINGS_INVALID);
    }

    @Test
    void recommendedInsulin_settingsNull_refuses() {
        UUID userId = UUID.randomUUID();
        when(userSettingsService.getUserSettings(userId)).thenReturn(null);

        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(60.0);
        request.setCurrentGlucose(12.0);
        request.setTargetGlucose(6.0);
        request.setUserId(userId.toString());

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(request))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.SETTINGS_INVALID);
    }

    // -- correction skipped when not hyperglycemic -----------------------------

    @Test
    void recommendedInsulin_belowTarget_reducesTheMealDose() {
        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        // Real-life: 36g carbs, glucose 5.4 mmol/L (in-range but below target 6.0).
        request.setCarbs(36.0);
        request.setCurrentGlucose(5.4);
        request.setTargetGlucose(6.0);
        request.setUserId(USER_ID.toString());

        var response = service.calculateRecommendedInsulin(request);
        // C2: carb bolus 36/22 ≈ 1.64, correction (5.4-6.0)/2.2 ≈ -0.27 -> 1.36.
        // Previously the below-target case was ignored entirely and this returned 3.0.
        assertThat(response.getRecommendedInsulin()).isCloseTo(1.36, within(0.01));
    }

    @Test
    void recommendedInsulin_nullCurrentGlucose_refuses() {
        var request = new che.glucosemonitorbe.dto.InsulinCalculationRequest();
        request.setCarbs(30.0);
        request.setCurrentGlucose(null);
        request.setTargetGlucose(6.0);
        request.setUserId(USER_ID.toString());

        // C2: dosing without a glucose reading is not something to do silently.
        assertThatThrownBy(() -> service.calculateRecommendedInsulin(request))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.INVALID_INPUT);
    }

    // -- C1: derived insulin:carb ratio ----------------------------------------

    /** Settings stub: 7-arg constructor is (id, userId, carbRatio, isf, halfLife, maxCob, bodyWeightKg). */
    private void givenSettings(Double carbRatio, Double isf, Double bodyWeightKg) {
        UserSettingsDTO settings = new UserSettingsDTO(
                UUID.randomUUID(), USER_ID, carbRatio, isf, 45, 240, bodyWeightKg);
        when(userSettingsService.getUserSettings(any(UUID.class))).thenReturn(settings);
    }

    private InsulinCalculationRequest doseRequest(double carbs, double currentGlucose, double targetGlucose) {
        return InsulinCalculationRequest.builder()
                .carbs(carbs)
                .currentGlucose(currentGlucose)
                .targetGlucose(targetGlucose)
                .activeInsulin(0.0)
                .userId(USER_ID.toString())
                .build();
    }

    private static DosingRefusalReason refusalReasonOf(Throwable ex) {
        return ((DosingRefusedException) ex).getReason();
    }

    @Test
    void mealDose_usesRatioDerivedFromIsfAndCarbRatio_notTheOldHardcoded12() {
        // carbRatio 2.0 mmol/L per 10 g, ISF 2.2 mmol/L/U -> 10 * 2.2 / 2.0 = 11.0 g/U
        givenSettings(2.0, 2.2, 70.0);

        // No correction: current == target. 55 g / 11 g/U = 5.0 U
        var response = service.calculateRecommendedInsulin(doseRequest(55.0, 6.0, 6.0));

        assertThat(response.getRecommendedInsulin()).isEqualTo(5.0);
    }

    @Test
    void insulinSensitiveUser_getsSmallerMealDoseThanTheOldConstant() {
        // carbRatio 1.0, ISF 2.5 -> 25 g/U. 50 g -> 2.0 U (the old code gave 50/12 = 4.17 U).
        givenSettings(1.0, 2.5, 70.0);

        var response = service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0));

        assertThat(response.getRecommendedInsulin()).isEqualTo(2.0);
    }

    @Test
    void derivedRatioBelowEnvelope_refuses_ratherThanClamping() {
        // carbRatio 8.0, ISF 1.0 -> 1.25 g/U, below the 3.0 floor
        givenSettings(8.0, 1.0, 70.0);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT);
    }

    @Test
    void derivedRatioAboveEnvelope_refuses() {
        // carbRatio 0.5, ISF 4.0 -> 80 g/U, above the 30.0 ceiling
        givenSettings(0.5, 4.0, 70.0);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT);
    }

    @Test
    void nonPositiveOrMissingSettings_refuse() {
        givenSettings(2.0, 0.0, 70.0);
        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.SETTINGS_INVALID);

        givenSettings(2.0, null, 70.0);
        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.SETTINGS_INVALID);
    }

    // -- C2: hypoglycemia handling ---------------------------------------------

    @Test
    void glucoseBelowHypoThreshold_refuses_noDoseReturned() {
        givenSettings(2.0, 2.2, 70.0);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(60.0, 3.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD);
    }

    @Test
    void glucoseExactlyAtThreshold_isAllowed() {
        givenSettings(2.0, 2.2, 70.0);
        // 3.9 is the refusal floor, not itself refused.
        assertThat(service.calculateRecommendedInsulin(doseRequest(0.0, 3.9, 3.9))
                .getRecommendedInsulin()).isEqualTo(0.0);
    }

    @Test
    void belowTargetButAboveThreshold_reducesTheMealDose() {
        givenSettings(2.0, 2.2, 70.0);
        // 11 g/U -> 55 g = 5.0 U meal. Correction (5.0 - 6.0) / 2.2 = -0.4545 U.
        // 5.0 - 0.4545 = 4.55 U after rounding.
        var response = service.calculateRecommendedInsulin(doseRequest(55.0, 5.0, 6.0));

        assertThat(response.getRecommendedInsulin()).isEqualTo(4.55);
    }

    @Test
    void largeNegativeCorrection_floorsAtZero_neverNegative() {
        givenSettings(2.0, 2.2, 70.0);
        // Tiny meal, well below target: correction dominates and would go negative.
        var response = service.calculateRecommendedInsulin(doseRequest(5.0, 4.0, 9.0));

        assertThat(response.getRecommendedInsulin()).isEqualTo(0.0);
    }

    @Test
    void missingCarbsOrGlucose_refusesAsInvalidInput() {
        givenSettings(2.0, 2.2, 70.0);
        InsulinCalculationRequest noCarbs = InsulinCalculationRequest.builder()
                .currentGlucose(6.0).targetGlucose(6.0).userId(USER_ID.toString()).build();

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(noCarbs))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
                .isEqualTo(DosingRefusalReason.INVALID_INPUT);
    }
}
