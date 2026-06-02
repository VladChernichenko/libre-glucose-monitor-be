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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    // ── exponential decay behaviour (BE-3 regression guards) ─────────────────

    /**
     * // BUG: BE-3 — CarbsOnBoardService used linear decay instead of exponential
     * half-life decay.  Fixed: now uses 2^(-t/halfLife) exponential model.
     * This regression test PASSES on the fixed code and documents the correct behaviour.
     */
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

    // ── N+1 fix: getCOBSettings invocation counts ─────────────────────────────

    /**
     * Core regression for the N+1 fix:
     * calculateTotalCarbsOnBoard with N entries must call getCOBSettings exactly once,
     * not once per entry.
     */
    @Test
    void totalCob_fiveEntries_loadsSettingsExactlyOnce() {
        List<CarbsEntry> entries = List.of(
                carbEntry(now.minusMinutes(30),  30.0, "MEDIUM", null),
                carbEntry(now.minusMinutes(60),  25.0, "MEDIUM", null),
                carbEntry(now.minusMinutes(90),  20.0, "SLOW",   null),
                carbEntry(now.minusMinutes(120), 15.0, "FAST",   null),
                carbEntry(now.minusMinutes(150), 10.0, "SLOW",   8.0)
        );

        service.calculateTotalCarbsOnBoard(entries, now, USER_ID);

        verify(settingsService, times(1)).getCOBSettings(USER_ID);
    }

    @Test
    void totalCob_oneEntry_loadsSettingsExactlyOnce() {
        List<CarbsEntry> entries = List.of(carbEntry(now.minusMinutes(30), 40.0, "MEDIUM", null));

        service.calculateTotalCarbsOnBoard(entries, now, USER_ID);

        verify(settingsService, times(1)).getCOBSettings(USER_ID);
    }

    @Test
    void totalCob_tenEntries_loadsSettingsExactlyOnce() {
        List<CarbsEntry> entries = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> carbEntry(now.minusMinutes(i * 15L), 20.0, "MEDIUM", null))
                .toList();

        service.calculateTotalCarbsOnBoard(entries, now, USER_ID);

        // Must be 1 regardless of list size — the N+1 guarantee
        verify(settingsService, times(1)).getCOBSettings(USER_ID);
    }

    /** Empty list returns early — DB should never be hit. */
    @Test
    void totalCob_emptyList_neverLoadsSettings() {
        service.calculateTotalCarbsOnBoard(List.of(), now, USER_ID);

        verify(settingsService, never()).getCOBSettings(any());
    }

    /** Null list returns early — DB should never be hit. */
    @Test
    void totalCob_nullList_neverLoadsSettings() {
        service.calculateTotalCarbsOnBoard(null, now, USER_ID);

        verify(settingsService, never()).getCOBSettings(any());
    }

    /** Single-entry public API still loads settings exactly once. */
    @Test
    void singleEntry_loadsSettingsExactlyOnce() {
        CarbsEntry e = carbEntry(now.minusMinutes(45), 50.0, "MEDIUM", null);

        service.calculateRemainingCarbs(e, now, USER_ID);

        verify(settingsService, times(1)).getCOBSettings(USER_ID);
    }

    /** Null entry guard fires before any settings load. */
    @Test
    void singleEntry_null_neverLoadsSettings() {
        service.calculateRemainingCarbs(null, now, USER_ID);

        verify(settingsService, never()).getCOBSettings(any());
    }

    /** Zero-carbs guard fires before any settings load. */
    @Test
    void singleEntry_zeroCarbs_neverLoadsSettings() {
        CarbsEntry e = carbEntry(now.minusMinutes(30), 0.0, "MEDIUM", null);

        service.calculateRemainingCarbs(e, now, USER_ID);

        verify(settingsService, never()).getCOBSettings(any());
    }

    /**
     * Batch with null/zero entries mixed in — the filter skips them.
     * Settings must still be loaded exactly once (not once per valid entry,
     * not once per total entry count).
     */
    @Test
    void totalCob_mixedValidAndZeroCarbs_loadsSettingsExactlyOnce() {
        CarbsEntry valid1 = carbEntry(now.minusMinutes(30), 40.0, "MEDIUM", null);
        CarbsEntry zero   = carbEntry(now.minusMinutes(45), 0.0,  "MEDIUM", null);
        CarbsEntry valid2 = carbEntry(now.minusMinutes(60), 20.0, "SLOW",   null);

        service.calculateTotalCarbsOnBoard(List.of(valid1, zero, valid2), now, USER_ID);

        verify(settingsService, times(1)).getCOBSettings(USER_ID);
    }

    // ── NotebookLM scenario 1: fiber >5g net-carb threshold ──────────────────

    /**
     * Clinical rule: when fiber > 5g, subtract it from total carbs before calculating COB.
     * The GI_GL_ENHANCED model always uses availableCarbs = carbs - fiber, so COB with
     * high-fiber content must be strictly lower than COB with zero fiber at the same carb count.
     */
    @Test
    void enhancedMode_fiberOver5g_reducesEffectiveCarbs() {
        // 25g carbs, 7g fiber (>5g threshold) — net 18g available
        CarbsEntry highFiber = giEnhancedEntry(now.minusMinutes(30), 25.0, 50.0, 7.0, 5.0, 3.0);
        // 25g carbs, 0g fiber — all 25g available
        CarbsEntry noFiber   = giEnhancedEntry(now.minusMinutes(30), 25.0, 50.0, 0.0, 5.0, 3.0);

        double cobHighFiber = service.calculateRemainingCarbs(highFiber, now, USER_ID);
        double cobNoFiber   = service.calculateRemainingCarbs(noFiber,   now, USER_ID);

        assertThat(cobHighFiber).isLessThan(cobNoFiber);
    }

    @Test
    void enhancedMode_fiberExceedsCarbs_cobIsZero() {
        // fiber=30g > carbs=25g → availableCarbs clamped to 0
        CarbsEntry e = giEnhancedEntry(now.minusMinutes(20), 25.0, 55.0, 30.0, 5.0, 3.0);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    // ── NotebookLM scenario 2: double-wave FPU >= 2 extends path to 8h ───────

    @Test
    void doubleWave_exactBoundary_carbsGoneAt480min() {
        // 8h = 480 min → at exactly 481 min → 0
        CarbsEntry e = carbEntry(now.minusMinutes(481), 60.0, "SLOW", 8.0);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    @Test
    void doubleWave_carbsStillPresentAt479min() {
        CarbsEntry e = carbEntry(now.minusMinutes(479), 60.0, "SLOW", 8.0);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isGreaterThan(0.0);
    }

    // ── NotebookLM scenario 3: IOB edge — zero halfLife returns 0 ─────────────

    @Test
    void zeroHalfLife_returnsZeroImmediately() {
        stubSettings(240, 0); // halfLife=0
        CarbsEntry e = carbEntry(now.minusMinutes(10), 50.0, "MEDIUM", null);
        assertThat(service.calculateRemainingCarbs(e, now, USER_ID)).isEqualTo(0.0);
    }

    // ── COB taper: smooth approach to zero (regression for the step-in-prediction bug) ──

    /**
     * In the 30-minute taper zone (maxDuration-30 … maxDuration) COB must be
     * strictly decreasing — no plateau or uptick that would create a step in the
     * prediction path.
     */
    @Test
    void cobTaper_inTaperZone_isStrictlyDecreasing() {
        // SLOW class → maxDuration=240, taperStart=210
        double prev = Double.MAX_VALUE;
        for (int t = 210; t <= 240; t += 5) {
            CarbsEntry e = carbEntry(now.minusMinutes(t), 60.0, "SLOW", null);
            double cob = service.calculateRemainingCarbs(e, now, USER_ID);
            assertThat(cob).isLessThan(prev);
            prev = cob;
        }
    }

    /**
     * COB must be exactly 0 at maxDuration via the taper (taperProgress=1.0),
     * and also at maxDuration+1 via the hard cutoff — no residual value leaks
     * past the window end.
     */
    @Test
    void cobTaper_atMaxDuration_isZero() {
        // Taper at exactly 240 min (taperProgress = (240-210)/30 = 1.0 → raw *= 0)
        CarbsEntry atMax = carbEntry(now.minusMinutes(240), 60.0, "SLOW", null);
        assertThat(service.calculateRemainingCarbs(atMax, now, USER_ID)).isEqualTo(0.0);

        // Hard cutoff beyond maxDuration
        CarbsEntry pastMax = carbEntry(now.minusMinutes(241), 60.0, "SLOW", null);
        assertThat(service.calculateRemainingCarbs(pastMax, now, USER_ID)).isEqualTo(0.0);
    }

    /**
     * The taper must significantly reduce the COB value at the last step before
     * maxDuration compared with the natural exponential decay.
     * Pre-fix, a 60g SLOW meal at t=235 min had ~1.5g COB which then snapped
     * to 0 at t=240 — visible as a step in the prediction path.
     * Post-fix, the taper must have reduced that 1.5g to ≤25% of the natural value.
     */
    @Test
    void cobTaper_nearEnd_isSignificantlyLessThanNaturalDecay() {
        // t=235 min → taperProgress = (235-210)/30 = 0.833 → raw *= 0.167
        CarbsEntry e = carbEntry(now.minusMinutes(235), 60.0, "SLOW", null);
        double tapered = service.calculateRemainingCarbs(e, now, USER_ID);

        // Natural decay at 235 min: 60 * 0.5^(235/45) ≈ 1.5g
        double natural = 60.0 * Math.pow(0.5, 235.0 / 45.0);

        assertThat(tapered).isLessThan(natural * 0.25);
    }

    /**
     * The key bug: the hard cutoff caused a single 5-min path step of
     * ~15.7g → 0g at maxDuration.  The taper reduces this boundary step
     * (minute 115 → 120) to ≤ 20% of the pre-fix natural decay value.
     *
     * The taper zone entry (minute 90 → 95) has a larger delta due to
     * taper progress beginning there — that's expected.  The regression
     * guard is specifically about the BOUNDARY step that the original bug
     * reported (a sudden spike in the prediction chart at meal expiry time).
     */
    @Test
    void cobTaper_boundaryStep_reducedToLessThan20PercentOfPreFix() {
        // Pre-fix: hard cutoff snapped from naturalAt120 → 0 in one step
        double naturalAt120 = 100.0 * Math.pow(0.5, 120.0 / 45.0); // ~15.7g

        // Post-fix: step from last point inside window (115 min) to maxDuration (120 min)
        CarbsEntry at115 = carbEntry(now.minusMinutes(115), 100.0, "FAST", null);
        CarbsEntry at120 = carbEntry(now.minusMinutes(120), 100.0, "FAST", null);
        double cobAt115 = service.calculateRemainingCarbs(at115, now, USER_ID);
        double cobAt120 = service.calculateRemainingCarbs(at120, now, USER_ID);

        double boundaryStepAfterFix = Math.abs(cobAt115 - cobAt120);

        // The taper must reduce the boundary step to < 20% of the pre-fix jump
        assertThat(boundaryStepAfterFix).isLessThan(naturalAt120 * 0.20);
        // And maxDuration itself must still be 0 (hard cutoff preserved)
        assertThat(cobAt120).isEqualTo(0.0);
    }

    /**
     * COB must be strictly non-negative throughout the entire absorption window
     * including the taper zone.
     */
    @Test
    void cobTaper_neverNegative_throughoutWindow() {
        for (int t = 0; t <= 245; t += 5) {
            CarbsEntry e = carbEntry(now.minusMinutes(t), 60.0, "SLOW", null);
            assertThat(service.calculateRemainingCarbs(e, now, USER_ID))
                    .as("COB must be >= 0 at t=%d min", t)
                    .isGreaterThanOrEqualTo(0.0);
        }
    }

    /**
     * The taper applies for the GI_GL_ENHANCED path too — verifies that the
     * enhanced mode also approaches 0 smoothly instead of snapping at maxDuration.
     */
    @Test
    void cobTaper_enhancedMode_alsoSmooth() {
        // GI_GL_ENHANCED, SLOW (maxDuration=240), taper zone 210-240 min
        double prev = Double.MAX_VALUE;
        for (int t = 210; t <= 240; t += 5) {
            CarbsEntry e = giEnhancedEntry(now.minusMinutes(t), 60.0, 55.0, 3.0, 10.0, 8.0);
            e.setAbsorptionSpeedClass("SLOW");
            double cob = service.calculateRemainingCarbs(e, now, USER_ID);
            assertThat(cob).isLessThanOrEqualTo(prev);
            prev = cob;
        }
        // Must reach 0 at maxDuration
        CarbsEntry atMax = giEnhancedEntry(now.minusMinutes(240), 60.0, 55.0, 3.0, 10.0, 8.0);
        atMax.setAbsorptionSpeedClass("SLOW");
        assertThat(service.calculateRemainingCarbs(atMax, now, USER_ID)).isEqualTo(0.0);
    }

    /**
     * Taper is correctly applied in the batch API (calculateTotalCarbsOnBoard):
     * the sum of two tapered entries must be strictly less than their combined
     * natural-decay value at t=235 min.
     */
    @Test
    void cobTaper_batchApi_taperedSumLessThanNatural() {
        CarbsEntry e1 = carbEntry(now.minusMinutes(235), 40.0, "SLOW", null);
        CarbsEntry e2 = carbEntry(now.minusMinutes(235), 20.0, "SLOW", null);

        double totalTapered = service.calculateTotalCarbsOnBoard(List.of(e1, e2), now, USER_ID);

        double natural1 = 40.0 * Math.pow(0.5, 235.0 / 45.0);
        double natural2 = 20.0 * Math.pow(0.5, 235.0 / 45.0);

        assertThat(totalTapered).isLessThan((natural1 + natural2) * 0.25);
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
