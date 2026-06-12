package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.service.COBSettingsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * TDD Red→Green tests for the EGP steady-state bug.
 *
 * <h3>Bug</h3>
 * When no long-acting insulin is logged, {@link BasalInsulinResolver#resolveEgpSuppression}
 * returns 0.0, causing {@code egpNow = EGP0 = 1.127 mmol/min} (>> f01 = 0.679 mmol/min).
 * Net +0.448 mmol/min drives glucose from 7.2 to ~16 mmol/L in 4 h — even with no COB or IOB.
 *
 * <h3>Fix</h3>
 * When {@code longActingNotes} is empty, default {@code egpNow = f01()} — the steady-state
 * assumption for a T1D patient on continuous background basal that isn't individually logged.
 */
@ExtendWith(MockitoExtension.class)
class HovorkaGlucosePredictionServiceTest {

    @Mock HovorkaParameterService paramService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock COBSettingsService cobSettingsService;

    private HovorkaGlucosePredictionService service;
    private HovorkaParameters params;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 1, 10, 0);

    @BeforeEach
    void setUp() {
        DallaManGutModel gutModel  = new DallaManGutModel();
        HovorkaOdeSolver solver    = new HovorkaOdeSolver(gutModel);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        service = new HovorkaGlucosePredictionService(
                paramService, solver, basal, insulinPrefsService, gutModel, cobSettingsService);

        double weight = 70.0;
        double vG  = HovorkaParameters.VG_PER_KG * weight;
        double f01 = HovorkaParameters.F01_PER_KG * weight;
        params = new HovorkaParameters(
                vG, f01, f01,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 0.80, 2.2, weight);

        when(insulinPrefsService.getRapidIobParameters(any()))
                .thenReturn(new RapidInsulinIobParameters(4.5, 55.0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RED before fix, GREEN after fix
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void noCobNoIob_noLongActing_glucoseRemainsNearBaseline() {
        // No carbs, no bolus, no long-acting notes → glucose must stay flat.
        // Before fix: egpNow = EGP0 = 1.127 >> f01 = 0.679 → net +0.448 mmol/min → FAILS.
        // After fix:  egpNow = f01 (steady-state default)   → glucose stays flat → PASSES.
        double g0 = 7.2;

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, g0, NOW,
                List.of(), List.of(), List.of(),
                USER_ID, 240);

        assertThat(curve).isNotEmpty();
        curve.forEach(pt ->
                assertThat(pt.getPredictedGlucose())
                        .as("G at %s (COB=0, IOB=0, no basal logged)", pt.getTimestamp())
                        .isBetween(5.7, 8.7));   // g0 ± 1.5 mmol/L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression: meal logged at exactly "now" (minsAgo=0) must not be dropped
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prospectiveMealAtNow_glucoseRisesDespiteTimestampEqualsNow() {
        // Bug: GlucosePredictService adds the prospective meal with timestamp=now.
        // minsAgo=0 → buildFutureCarbTimeline stored it at key=0.
        // Integration loop starts at min=1 → key=0 is NEVER consumed → flat curve.
        // Fix: clamp futureMin to max(1, abs(minsAgo)).
        double g0 = 5.8;

        CarbsEntry meal = CarbsEntry.builder()
                .timestamp(NOW)   // exactly "now" → minsAgo=0 → was silently dropped before fix
                .carbs(35.0)
                .build();

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, g0, NOW,
                List.of(meal), List.of(), List.of(),
                USER_ID, 240);

        assertThat(curve).isNotEmpty();
        double peakG = curve.stream()
                .mapToDouble(PredictionPointDTO::getPredictedGlucose)
                .max().orElse(g0);
        // 35g carbs with no insulin must drive glucose above baseline
        assertThat(peakG).isGreaterThan(g0 + 1.0);
    }

    @Test
    void pastCarbEntry_minsAgoNegative_deliveredAtCorrectFutureMinute() {
        // A meal timestamp 30 min in the future (minsAgo=-30) must be delivered at min=30.
        double g0 = 5.5;

        CarbsEntry futureMeal = CarbsEntry.builder()
                .timestamp(NOW.plusMinutes(30))   // 30 min in future
                .carbs(40.0)
                .build();

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, g0, NOW,
                List.of(futureMeal), List.of(), List.of(),
                USER_ID, 240);

        // Glucose should be flat for first ~30 min then rise
        PredictionPointDTO at25min = curve.stream()
                .filter(p -> p.getTimestamp().equals(NOW.plusMinutes(25))).findFirst().orElse(null);
        PredictionPointDTO at120min = curve.stream()
                .filter(p -> p.getTimestamp().equals(NOW.plusMinutes(120))).findFirst().orElse(null);

        if (at25min != null && at120min != null) {
            assertThat(at120min.getPredictedGlucose()).isGreaterThan(at25min.getPredictedGlucose());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression: IOB timeline must not "fold" a past dose back toward full IOB
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("past insulin dose: glucose falls from the first prediction point (no flat 'fold' artifact)")
    void pastInsulinDose_glucoseFallsImmediately_noFlatFoldArtifact() {
        // Bolus given 35 min ago, no carbs, no long-acting basal logged.
        // Bug: minsAgoAtStep = minsAgoDose - m made IOB *rise* toward the full
        // dose for m in [0, 35), so iobActivityRate = max(0, IOB[m]-IOB[m+1]) was
        // 0 for the first ~35 min — a flat prediction even though insulin (already
        // near its activity peak) should be lowering glucose immediately.
        double g0 = 8.3;

        InsulinDose dose = InsulinDose.builder()
                .timestamp(NOW.minusMinutes(35))
                .units(3.0)
                .build();

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, g0, NOW,
                List.of(), List.of(dose), List.of(),
                USER_ID, 60);

        assertThat(curve).isNotEmpty();
        assertThat(curve.get(0).getPredictedGlucose())
                .as("G at t=5min must already be below baseline (%.1f) — insulin given 35 min ago "
                    + "is near its activity peak and should be lowering glucose immediately, "
                    + "not held flat for ~35 min.", g0)
                .isLessThan(g0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression: prospective insulin dose (minsAgoDose < 0) must not act early
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("future-timestamped insulin dose: prediction matches no-dose curve until delivery, "
                  + "then diverges lower")
    void prospectiveInsulinDose_matchesBaselineUntilDelivery_thenDivergesLower() {
        // A bolus dosed 20 min in the future (minsAgoDose = -20) must contribute zero
        // IOB/activity until its delivery minute — mirroring
        // pastCarbEntry_minsAgoNegative_deliveredAtCorrectFutureMinute for carbs.
        double g0 = 8.0;

        InsulinDose futureDose = InsulinDose.builder()
                .timestamp(NOW.plusMinutes(20))
                .units(3.0)
                .build();

        List<PredictionPointDTO> withDose = service.buildPredictionPath(
                params, g0, NOW, List.of(), List.of(futureDose), List.of(), USER_ID, 60);
        List<PredictionPointDTO> noDose = service.buildPredictionPath(
                params, g0, NOW, List.of(), List.of(), List.of(), USER_ID, 60);

        // Before delivery (t=5,15min): identical to the no-dose curve — the prospective
        // dose must contribute zero IOB/activity before its delivery minute.
        assertThat(glucoseAt(withDose, 5))
                .as("G at t=5min must match the no-dose curve — dose is not delivered for 20 more minutes")
                .isEqualTo(glucoseAt(noDose, 5));
        assertThat(glucoseAt(withDose, 15))
                .as("G at t=15min must match the no-dose curve — dose is not delivered for 5 more minutes")
                .isEqualTo(glucoseAt(noDose, 15));

        // After delivery (t=60min): the dose has been active ~40 min and must lower
        // glucose relative to the no-dose curve.
        assertThat(glucoseAt(withDose, 60))
                .as("G at t=60min must be lower than the no-dose curve — dose delivered at t=20min "
                    + "has been active ~40 min")
                .isLessThan(glucoseAt(noDose, 60));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression: IOB activity rate must align with the simulation step it's applied to
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("regression: insulin activity rate at step `min` reflects IOB decay during "
                  + "[min-1,min], not [min,min+1]")
    void iobActivityRate_alignedWithSimulationStep_notOneMinuteAhead() {
        double g0 = 8.0;
        double units = 3.0;
        long minsAgoDose = 20;
        double diaHours = 4.5;
        double peakMinutes = 55.0;

        InsulinDose dose = InsulinDose.builder()
                .timestamp(NOW.minusMinutes(minsAgoDose))
                .units(units)
                .build();

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, g0, NOW, List.of(), List.of(dose), List.of(), USER_ID, 60);

        // First emitted point is at min=5 (DENSE_STEP_MIN). iob[m] = IOB at "now + m minutes".
        double iob4 = InsulinCalculatorService.iobOpenApsExponential(units, minsAgoDose + 4, diaHours, peakMinutes);
        double iob5 = InsulinCalculatorService.iobOpenApsExponential(units, minsAgoDose + 5, diaHours, peakMinutes);

        // The activity applied during the step that produces the t=5min point (step min=5)
        // must be the IOB decay during [now+4,now+5] — iob[4]-iob[5] — not [now+5,now+6].
        double activityRate = Math.max(0.0, iob4 - iob5);
        double insulinEffect = params.isf() * params.effectiveInsulinVolume() * activityRate;
        double insulinEff = -insulinEffect * 5; // DENSE_STEP_MIN
        double expected = Math.round(insulinEff * 100.0) / 100.0;

        assertThat(curve.get(0).getInsulinActivityEffect())
                .as("insulinActivityEffect at t=5min must reflect IOB decay during [now+4,now+5]")
                .isEqualTo(expected);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression: manual per-meal-window ISF override (isfBreakfast/isfLunch/isfDinner)
    // must apply to the Hovorka insulin-effect term, not just the OpenAPS path.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("regression: a dose given in a meal window with an ISF override uses "
                  + "that override, not a single static ISF resolved once at request time")
    void insulinEffect_usesPerMealWindowIsfOverride_notStaticIsf() {
        double g0 = 8.0;
        double units = 3.0;
        long minsAgoDose = 20;
        double diaHours = 4.5;
        double peakMinutes = 55.0;

        // NOW = 2024-06-01 10:00 -> dose given at 09:40, BREAKFAST (05:00-11:00).
        InsulinDose dose = InsulinDose.builder()
                .timestamp(NOW.minusMinutes(minsAgoDose))
                .units(units)
                .build();

        COBSettingsDTO settings = new COBSettingsDTO();
        settings.setIsf(params.isf());   // 2.2, matches the Hovorka-calibrated fallback
        settings.setIsfBreakfast(0.5);   // manual override for the morning window

        when(cobSettingsService.getCOBSettings(USER_ID)).thenReturn(settings);

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, g0, NOW, List.of(), List.of(dose), List.of(), USER_ID, 60);

        double iob4 = InsulinCalculatorService.iobOpenApsExponential(units, minsAgoDose + 4, diaHours, peakMinutes);
        double iob5 = InsulinCalculatorService.iobOpenApsExponential(units, minsAgoDose + 5, diaHours, peakMinutes);
        double activityRate = Math.max(0.0, iob4 - iob5);

        // Expected using isfBreakfast(0.5), resolved from the dose's own 09:40 timestamp —
        // NOT the static Hovorka-calibrated isf (2.2).
        double expectedEffect = -(0.5 * params.effectiveInsulinVolume() * activityRate) * 5;
        double expectedRounded = Math.round(expectedEffect * 100.0) / 100.0;

        assertThat(curve.get(0).getInsulinActivityEffect())
                .as("insulinActivityEffect at t=5min must use isfBreakfast (0.5), resolved "
                    + "from the dose's 09:40 timestamp, not the static Hovorka-calibrated "
                    + "isf (%.1f)", params.isf())
                .isEqualTo(expectedRounded);
    }

    @Test
    @DisplayName("regression: a dose's ISF is resolved from its OWN administration time, "
                  + "not the wall-clock time its insulin activity is later consumed — a "
                  + "dinner-time correction bolus keeps isfDinner even once its activity "
                  + "plays out after the dinner window ends")
    void insulinEffect_resolvesIsfFromDoseTimestamp_evenWhenActivityCrossesIntoNight() {
        double g0 = 11.1;
        double units = 1.0;
        long minsAgoDose = 20;
        double diaHours = 4.5;
        double peakMinutes = 55.0;

        // "now" = 21:55 (DINNER, 16:00-21:59). First emitted point is now+5min = 22:00,
        // which is NIGHT (22:00-04:59, no ISF override) per MealWindow.
        LocalDateTime now = LocalDateTime.of(2024, 6, 1, 21, 55);

        // Dose given at 21:35 — still within DINNER.
        InsulinDose dose = InsulinDose.builder()
                .timestamp(now.minusMinutes(minsAgoDose))
                .units(units)
                .build();

        COBSettingsDTO settings = new COBSettingsDTO();
        settings.setIsf(params.isf());   // 2.2, the NIGHT fallback (no override for NIGHT)
        settings.setIsfDinner(4.0);      // manual override for the dinner window

        when(cobSettingsService.getCOBSettings(USER_ID)).thenReturn(settings);

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, g0, now, List.of(), List.of(dose), List.of(), USER_ID, 60);

        double iob4 = InsulinCalculatorService.iobOpenApsExponential(units, minsAgoDose + 4, diaHours, peakMinutes);
        double iob5 = InsulinCalculatorService.iobOpenApsExponential(units, minsAgoDose + 5, diaHours, peakMinutes);
        double activityRate = Math.max(0.0, iob4 - iob5);

        // Expected using isfDinner(4.0), resolved from the dose's 21:35 timestamp — NOT
        // the NIGHT fallback isf (2.2) that applies at the 22:00 consumption time.
        double expectedEffect = -(4.0 * params.effectiveInsulinVolume() * activityRate) * 5;
        double expectedRounded = Math.round(expectedEffect * 100.0) / 100.0;

        assertThat(curve.get(0).getInsulinActivityEffect())
                .as("insulinActivityEffect at t=22:00 (NIGHT, no override) must still use "
                    + "isfDinner (4.0) because the dose was given at 21:35 in DINNER, not "
                    + "the NIGHT fallback isf (%.1f)", params.isf())
                .isEqualTo(expectedRounded);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: Gap 1 — K_ABS scaling with tMaxG (FPU gastric-emptying effect)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("high-fat params (tMaxG × 3) → glucose peak appears later than pure-carb params")
    void highFatTMaxG_glucosePeakShiftsRight() {
        double baseTMaxG = DallaManGutModel.BASE_T_MAX_G_MIN;      // ≈ 26.8 min (pure carb)
        double hfTMaxG   = baseTMaxG * 3.0;                        // ≈ 80.4 min (high fat/protein)

        HovorkaParameters baseP = paramsWithTMaxG(baseTMaxG);
        HovorkaParameters hfP   = paramsWithTMaxG(hfTMaxG);

        // 60 g carbs at t=0; no insulin
        CarbsEntry meal = CarbsEntry.builder().timestamp(NOW).carbs(60.0).build();

        List<PredictionPointDTO> curveBase = service.buildPredictionPath(
                baseP, 5.5, NOW, List.of(meal), List.of(), List.of(), USER_ID, 300);
        List<PredictionPointDTO> curveHF = service.buildPredictionPath(
                hfP,   5.5, NOW, List.of(meal), List.of(), List.of(), USER_ID, 300);

        int peakMinBase = peakMinute(curveBase);
        int peakMinHF   = peakMinute(curveHF);

        assertThat(peakMinHF)
                .as("High-fat tMaxG (%.1f min) must shift the glucose peak right of pure-carb (%.1f min). "
                    + "Base peak at %d min, HF peak at %d min.",
                    hfTMaxG, baseTMaxG, peakMinBase, peakMinHF)
                .isGreaterThan(peakMinBase);
    }

    @Test
    @DisplayName("high-fat params → lower early glucose (t=60 min) than pure-carb")
    void highFatTMaxG_lowerEarlyGlucose() {
        double baseTMaxG = DallaManGutModel.BASE_T_MAX_G_MIN;
        double hfTMaxG   = baseTMaxG * 3.0;

        CarbsEntry meal = CarbsEntry.builder().timestamp(NOW).carbs(60.0).build();

        List<PredictionPointDTO> curveBase = service.buildPredictionPath(
                paramsWithTMaxG(baseTMaxG), 5.5, NOW,
                List.of(meal), List.of(), List.of(), USER_ID, 300);
        List<PredictionPointDTO> curveHF = service.buildPredictionPath(
                paramsWithTMaxG(hfTMaxG), 5.5, NOW,
                List.of(meal), List.of(), List.of(), USER_ID, 300);

        double gAt60_base = glucoseAt(curveBase, 60);
        double gAt60_hf   = glucoseAt(curveHF,   60);

        assertThat(gAt60_hf)
                .as("At t=60 min, slower absorption (tMaxG×3) must yield lower G than fast absorption. "
                    + "Base G=%.2f, HF G=%.2f", gAt60_base, gAt60_hf)
                .isLessThan(gAt60_base);
    }

    @Test
    @DisplayName("buildWarmState replays Qgut drain with the same kAbsEff as forward integration")
    void warmStateReplay_usesSameKAbsEffAsForwardIntegration_forHighFatMeal() {
        // High-fat/protein meal: tMaxG x3 -> kAbsEff = K_ABS / 3 (much slower Qgut drain).
        double hfTMaxG = DallaManGutModel.BASE_T_MAX_G_MIN * 3.0;
        HovorkaParameters hfP = paramsWithTMaxG(hfTMaxG);

        // Reference: continuous run starting at the meal time.
        CarbsEntry mealAtZero = CarbsEntry.builder().timestamp(NOW).carbs(60.0).build();
        List<PredictionPointDTO> reference = service.buildPredictionPath(
                hfP, 5.5, NOW, List.of(mealAtZero), List.of(), List.of(), USER_ID, 120);

        // Replay: same meal, but "now" is 60 min after the meal -> buildWarmState replays
        // the Dalla Man gut ODE for 60 min before the forward integration begins.
        LocalDateTime laterNow = NOW.plusMinutes(60);
        List<PredictionPointDTO> replay = service.buildPredictionPath(
                hfP, 5.5, laterNow, List.of(mealAtZero), List.of(), List.of(), USER_ID, 60);

        // Qgut (and thus carbAbsorptionEffect) is decoupled from the glucose compartments,
        // so "60 min after the meal" should report the same absorption effect whether
        // reached via continuous forward integration (reference @ t=65) or via
        // warm-up replay + 5 min forward (replay @ t=5).
        double refEffectAt65   = carbEffectAt(reference, 65);
        double replayEffectAt5 = carbEffectAt(replay, 5);

        assertThat(replayEffectAt5)
                .as("Warm-up replay must drain Qgut with the same kAbsEff as forward "
                    + "integration. reference(t=65min from meal)=%.4f, "
                    + "replay(t=5min after 60min warm-up)=%.4f",
                    refEffectAt65, replayEffectAt5)
                .isCloseTo(refEffectAt65, org.assertj.core.data.Percentage.withPercentage(20));
    }

    @Test
    @DisplayName("same-tMaxG params produce the same curve (K_ABS scaling is deterministic)")
    void sameTMaxG_identicalCurves() {
        double tMaxG = DallaManGutModel.BASE_T_MAX_G_MIN * 2.0;
        CarbsEntry meal = CarbsEntry.builder().timestamp(NOW).carbs(50.0).build();

        List<PredictionPointDTO> curve1 = service.buildPredictionPath(
                paramsWithTMaxG(tMaxG), 6.0, NOW,
                List.of(meal), List.of(), List.of(), USER_ID, 240);
        List<PredictionPointDTO> curve2 = service.buildPredictionPath(
                paramsWithTMaxG(tMaxG), 6.0, NOW,
                List.of(meal), List.of(), List.of(), USER_ID, 240);

        assertThat(curve1).hasSameSizeAs(curve2);
        for (int i = 0; i < curve1.size(); i++) {
            assertThat(curve1.get(i).getPredictedGlucose())
                    .isEqualTo(curve2.get(i).getPredictedGlucose());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression guard: EGP suppression still active when basal IS logged
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void noCobNoIob_longActingLogged_plateauEgpSuppression_glucoseAlsoStaysFlat() {
        // A long-acting note injected 6h ago → suppressionCurve returns PEAK_X3_BASAL (0.40)
        // egpNow = EGP0 * (1 - 0.40) ≈ f01 → steady state → glucose flat.
        // This guards that the EGP suppression path still works correctly after the fix.
        double g0 = 7.2;

        che.glucosemonitorbe.entity.Note longActing = new che.glucosemonitorbe.entity.Note();
        longActing.setTimestamp(NOW.minusHours(6));
        longActing.setInsulin(10.0);
        longActing.setType(che.glucosemonitorbe.entity.Note.TYPE_LONG_ACTING);

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, g0, NOW,
                List.of(), List.of(), List.of(longActing),
                USER_ID, 240);

        assertThat(curve).isNotEmpty();
        curve.forEach(pt ->
                assertThat(pt.getPredictedGlucose())
                        .as("G at %s (basal logged 6h ago)", pt.getTimestamp())
                        .isBetween(5.7, 8.7));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression guard: logging today's long-acting dose must not itself trigger
    // a rising forecast
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void noCobNoIob_longActingLoggedMinutesAgo_doesNotTriggerRise() {
        // A long-acting (Tresiba) note injected 42 min ago. Before the fix, the ramp-up
        // arm of suppressionCurve() returned ~0.14 (instead of the plateau 0.40), so
        // egpNow > f01 and the forecast rose by several mmol/L over 4 h — even though
        // the user just took their normal daily basal dose.
        //
        // After the fix: a freshly logged dose is in the plateau region from t=0,
        // so egpNow ≈ f01 and glucose stays flat — same as the no-notes default.
        double g0 = 4.6;

        che.glucosemonitorbe.entity.Note longActing = new che.glucosemonitorbe.entity.Note();
        longActing.setTimestamp(NOW.minusMinutes(42));
        longActing.setInsulin(22.0);
        longActing.setType(che.glucosemonitorbe.entity.Note.TYPE_LONG_ACTING);

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, g0, NOW,
                List.of(), List.of(), List.of(longActing),
                USER_ID, 240);

        assertThat(curve).isNotEmpty();
        curve.forEach(pt ->
                assertThat(pt.getPredictedGlucose())
                        .as("G at %s (basal logged 42min ago)", pt.getTimestamp())
                        .isBetween(3.1, 6.1));   // g0 ± 1.5 mmol/L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Build params with a given tMaxG and all other fields at population defaults. */
    private HovorkaParameters paramsWithTMaxG(double tMaxG) {
        double weight = 70.0;
        return new HovorkaParameters(
                HovorkaParameters.VG_PER_KG  * weight,
                HovorkaParameters.F01_PER_KG * weight,
                HovorkaParameters.F01_PER_KG * weight,   // egpNet = f01 (steady state)
                HovorkaParameters.K12_POP,
                HovorkaParameters.K21_POP,
                tMaxG, 0.80, 2.2, weight);
    }

    /** Index of the prediction point with the highest glucose value [minutes from NOW]. */
    private int peakMinute(List<PredictionPointDTO> curve) {
        return curve.stream()
                .max(Comparator.comparingDouble(PredictionPointDTO::getPredictedGlucose))
                .map(pt -> (int) java.time.Duration.between(NOW, pt.getTimestamp()).toMinutes())
                .orElse(0);
    }

    /** Predicted glucose at exactly t = minutesFromNow, or NaN if the point is not emitted. */
    private double glucoseAt(List<PredictionPointDTO> curve, int minutesFromNow) {
        return curve.stream()
                .filter(pt -> pt.getTimestamp().equals(NOW.plusMinutes(minutesFromNow)))
                .mapToDouble(PredictionPointDTO::getPredictedGlucose)
                .findFirst().orElse(Double.NaN);
    }

    /** Carb absorption effect at t = minutesFromStart of the curve, or NaN if not emitted. */
    private double carbEffectAt(List<PredictionPointDTO> curve, int minutesFromStart) {
        LocalDateTime start = curve.get(0).getTimestamp().minusMinutes(5); // first point is at +5min
        return curve.stream()
                .filter(pt -> pt.getTimestamp().equals(start.plusMinutes(minutesFromStart)))
                .mapToDouble(PredictionPointDTO::getCarbAbsorptionEffect)
                .findFirst().orElse(Double.NaN);
    }
}
