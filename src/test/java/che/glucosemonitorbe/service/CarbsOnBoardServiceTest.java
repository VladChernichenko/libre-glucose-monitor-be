package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CarbsOnBoardService covering:
 * - Speed class COB windows (FAST=120, MEDIUM=180, SLOW/null=240 min)
 * - Pattern duration override (Warsaw Method: 3h/4h/5h/8h tiers)
 * - User-configured maxCOBDuration wins over speed class default
 * - Enhanced (GI_GL_ENHANCED) absorption mode vs default exponential decay
 * - Edge cases: null entry, zero carbs, future timestamp
 */
class CarbsOnBoardServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private COBSettingsService settingsService;
    private CarbsOnBoardService service;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        settingsService = mock(COBSettingsService.class);
        service = new CarbsOnBoardService(settingsService);
        now = LocalDateTime.now();
        // Default: no user-configured maxCOBDuration (0 → use speed class), halfLife=45 min
        stubSettings(0, 45);
    }

    // ── null / zero guards ────────────────────────────────────────────────────

    @Test
    void nullEntry_returnsZero() {
        assertThat(service.calculateRemainingCarbs(null, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void nullCarbs_returnsZero() {
        CarbsEntry e = CarbsEntry.builder().timestamp(now.minusMinutes(30)).build();
        e.setCarbs(null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void zeroCarbs_returnsZero() {
        CarbsEntry e = carbEntry(now.minusMinutes(30), 0.0, "MEDIUM", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void futureTimestamp_returnsZero() {
        CarbsEntry e = carbEntry(now.plusMinutes(10), 50.0, "MEDIUM", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    // ── speed class COB windows ───────────────────────────────────────────────

    @Test
    void fastClass_windowIs120Min_carbsGoneAfterWindow() {
        CarbsEntry e = carbEntry(now.minusMinutes(121), 50.0, "FAST", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void fastClass_carbsStillPresentAt60Min() {
        CarbsEntry e = carbEntry(now.minusMinutes(60), 50.0, "FAST", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isGreaterThan(0.0);
    }

    @Test
    void mediumClass_windowIs180Min_carbsGoneAfterWindow() {
        CarbsEntry e = carbEntry(now.minusMinutes(181), 50.0, "MEDIUM", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void mediumClass_carbsStillPresentAt90Min() {
        CarbsEntry e = carbEntry(now.minusMinutes(90), 50.0, "MEDIUM", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isGreaterThan(0.0);
    }

    @Test
    void slowClass_windowIs240Min_carbsGoneAfterWindow() {
        CarbsEntry e = carbEntry(now.minusMinutes(241), 60.0, "SLOW", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void nullSpeedClass_treatedAsSlow_240MinWindow() {
        CarbsEntry e = carbEntry(now.minusMinutes(241), 60.0, null, null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    /**
     * At 150 min: FAST already expired, MEDIUM and SLOW still active.
     * Verifies the ordering FAST(120) < MEDIUM(180) < SLOW(240).
     */
    @Test
    void speedClassWindowOrdering_fastExpiredMediumSlowStillActive() {
        CarbsEntry fast   = carbEntry(now.minusMinutes(150), 50.0, "FAST",   null);
        CarbsEntry medium = carbEntry(now.minusMinutes(150), 50.0, "MEDIUM", null);
        CarbsEntry slow   = carbEntry(now.minusMinutes(150), 50.0, "SLOW",   null);

        assertThat(service.calculateRemainingCarbs(fast,   now, USER_ID)).isEqualTo(0.0);
        assertThat(service.calculateRemainingCarbs(medium, now, USER_ID)).isGreaterThan(0.0);
        assertThat(service.calculateRemainingCarbs(slow,   now, USER_ID)).isGreaterThan(0.0);
    }

    /** At exactly the window boundary the carbs must be 0. */
    @ParameterizedTest(name = "speedClass={0} → zero at {1}+1 min")
    @CsvSource({
            "FAST,   120",
            "MEDIUM, 180",
            "SLOW,   240",
    })
    void speedClassWindowEnforced_atWindowPlusOne(String speedClass, int window) {
        CarbsEntry e = carbEntry(now.minusMinutes(window + 1), 50.0, speedClass, null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    // ── Warsaw Method pattern duration override ───────────────────────────────

    @Test
    void doubleWave8h_carbsActiveAt6h() {
        // Double Wave (≥4 FPU): 8h = 480 min → carbs still present at 360 min
        CarbsEntry e = carbEntry(now.minusMinutes(360), 60.0, "SLOW", 8.0);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isGreaterThan(0.0);
    }

    @Test
    void doubleWave8h_carbsGoneAfter8h() {
        CarbsEntry e = carbEntry(now.minusMinutes(481), 60.0, "SLOW", 8.0);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void flatPlateau5h_carbsActiveAt4h() {
        // Flat Plateau (≥3 FPU): 5h = 300 min → active at 240 min
        CarbsEntry e = carbEntry(now.minusMinutes(240), 50.0, "MEDIUM", 5.0);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isGreaterThan(0.0);
    }

    @Test
    void flatPlateau5h_carbsGoneAfter5h() {
        CarbsEntry e = carbEntry(now.minusMinutes(301), 50.0, "MEDIUM", 5.0);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void moderateFpu4h_carbsActiveAt3h() {
        // Moderate FPU (2 FPU): 4h = 240 min → active at 180 min
        CarbsEntry e = carbEntry(now.minusMinutes(180), 50.0, "MEDIUM", 4.0);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isGreaterThan(0.0);
    }

    @Test
    void lightFpu3h_extendsWindowBeyondFastClass() {
        // FAST class = 120 min; Light FPU pattern = 3h = 180 min
        // At 150 min: FAST alone → expired; with pattern 3h → still active
        CarbsEntry withoutPattern = carbEntry(now.minusMinutes(150), 50.0, "FAST", null);
        CarbsEntry withPattern    = carbEntry(now.minusMinutes(150), 50.0, "FAST", 3.0);

        assertThat(service.calculateRemainingCarbs(withoutPattern, now, USER_ID)).isEqualTo(0.0);
        assertThat(service.calculateRemainingCarbs(withPattern,    now, USER_ID)).isGreaterThan(0.0);
    }

    @Test
    void patternShorterThanDefault_defaultWindowWins() {
        // Pattern=1h (60 min) but SLOW default=240 min → max(60,240)=240 → still active at 90 min
        CarbsEntry e = carbEntry(now.minusMinutes(90), 50.0, "SLOW", 1.0);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isGreaterThan(0.0);
    }

    // ── user-configured maxCOBDuration ────────────────────────────────────────

    @Test
    void userMaxCobDuration_overridesSpeedClassDefault() {
        stubSettings(120, 45); // user configured 120 min max
        // At 150 min: speed class SLOW=240, but user cap=120 → 0
        CarbsEntry e = carbEntry(now.minusMinutes(150), 50.0, "SLOW", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void userMaxCobDuration_isHardCutoff() {
        stubSettings(240, 45);
        CarbsEntry e = carbEntry(now.minusMinutes(250), 50.0, "FAST", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    // ── exponential decay behaviour ───────────────────────────────────────────

    @Test
    void moreRemainingAtEarlierTime() {
        CarbsEntry at30min = carbEntry(now.minusMinutes(30), 50.0, "MEDIUM", null);
        CarbsEntry at60min = carbEntry(now.minusMinutes(60), 50.0, "MEDIUM", null);

        assertThat(service.calculateRemainingCarbs(at30min, now, USER_ID))
                .isGreaterThan(service.calculateRemainingCarbs(at60min, now, USER_ID));
    }

    @Test
    void atOneHalfLife_remainingIsApproxHalf() {
        // halfLife=45 min → at t=45 min: remaining ≈ 50% of carbs
        CarbsEntry e = carbEntry(now.minusMinutes(45), 50.0, "SLOW", null);
        double remaining = service.calculateRemainingCarbs(e, now, USER_ID);
        assertThat(remaining).isCloseTo(25.0, within(3.0));
    }

    @Test
    void cobNeverExceedsInitialCarbs() {
        CarbsEntry e = carbEntry(now.minusMinutes(10), 80.0, "MEDIUM", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isLessThanOrEqualTo(80.0);
    }

    @Test
    void cobNeverNegative() {
        CarbsEntry e = carbEntry(now.minusMinutes(200), 60.0, "FAST", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isGreaterThanOrEqualTo(0.0);
    }

    // ── GI_GL_ENHANCED absorption mode ───────────────────────────────────────

    @Test
    void enhancedMode_returnsBoundedCob() {
        CarbsEntry e = giEnhancedEntry(now.minusMinutes(40), 60.0, 70.0, 5.0, 12.0, 10.0);
        double remaining = service.calculateRemainingCarbs(e, now, USER_ID);
        assertThat(remaining).isBetween(0.0, 60.0);
    }

    @Test
    void enhancedMode_highFiberAndFatProtein_moreCobRemainingThanPureHighGI() {
        // High-GI fast meal: peaks and clears quickly
        // Low-GI with fiber/protein/fat: delayed absorption → more COB at 90 min
        CarbsEntry highGi = giEnhancedEntry(now.minusMinutes(90), 50.0, 80.0, 1.0,  2.0, 1.0);
        CarbsEntry lowGi  = giEnhancedEntry(now.minusMinutes(90), 50.0, 30.0, 8.0, 20.0, 15.0);

        double cobHigh = service.calculateRemainingCarbs(highGi, now, USER_ID);
        double cobLow  = service.calculateRemainingCarbs(lowGi,  now, USER_ID);
        assertThat(cobLow).isGreaterThan(cobHigh);
    }

    @Test
    void enhancedMode_sameCarbsSameGI_moreFatProteinSlowsAbsorption() {
        CarbsEntry lean = giEnhancedEntry(now.minusMinutes(60), 50.0, 55.0, 1.0, 2.0,  2.0);
        CarbsEntry rich = giEnhancedEntry(now.minusMinutes(60), 50.0, 55.0, 3.0, 20.0, 15.0);

        assertThat(service.calculateRemainingCarbs(rich, now, USER_ID))
                .isGreaterThan(service.calculateRemainingCarbs(lean, now, USER_ID));
    }

    // ── total COB aggregation ─────────────────────────────────────────────────

    @Test
    void totalCob_equalsSum_ofIndividualEntries() {
        CarbsEntry e1 = carbEntry(now.minusMinutes(30), 30.0, "MEDIUM", null);
        CarbsEntry e2 = carbEntry(now.minusMinutes(60), 20.0, "MEDIUM", null);

        double expected = service.calculateRemainingCarbs(e1, now, USER_ID)
                        + service.calculateRemainingCarbs(e2, now, USER_ID);

        assertThat(service.calculateTotalCarbsOnBoard(List.of(e1, e2), now, USER_ID))
                .isCloseTo(expected, within(1e-9));
    }

    @Test
    void totalCob_emptyList_returnsZero() {
        assertThat(service.calculateTotalCarbsOnBoard(List.of(), now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void totalCob_nullList_returnsZero() {
        assertThat(service.calculateTotalCarbsOnBoard(null, now, USER_ID)).isEqualTo(0.0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubSettings(int maxCobMinutes, int halfLife) {
        when(settingsService.getCOBSettings(USER_ID))
                .thenReturn(new COBSettingsDTO(UUID.randomUUID(), USER_ID, 2.0, 1.0, halfLife, maxCobMinutes));
    }

    private CarbsEntry carbEntry(LocalDateTime timestamp, double carbs, String speedClass, Double patternDuration) {
        CarbsEntry e = CarbsEntry.builder()
                .timestamp(timestamp)
                .carbs(carbs)
                .build();
        e.setAbsorptionSpeedClass(speedClass);
        e.setSuggestedDurationHours(patternDuration);
        return e;
    }

    private CarbsEntry giEnhancedEntry(LocalDateTime timestamp, double carbs,
                                       double gi, double fiber, double protein, double fat) {
        CarbsEntry e = CarbsEntry.builder()
                .timestamp(timestamp)
                .carbs(carbs)
                .build();
        e.setAbsorptionMode("GI_GL_ENHANCED");
        e.setEstimatedGi(gi);
        e.setFiber(fiber);
        e.setProtein(protein);
        e.setFat(fat);
        e.setAbsorptionSpeedClass("MEDIUM");
        return e;
    }
}
