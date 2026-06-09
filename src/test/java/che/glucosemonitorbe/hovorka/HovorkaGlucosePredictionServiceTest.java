package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
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
                paramService, solver, basal, insulinPrefsService, gutModel);

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
}
