package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for the activity term against the real Hovorka ODE (no DB): the extra drop during
 * exercise, the persistent post-exercise effect, the insulin-independent drop at low IOB, ODE stability
 * under sustained activity, and bit-identical predictions with the NONE provider.
 */
class HovorkaActivityIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2025, 1, 1, 12, 0);
    private static final UUID USER = UUID.randomUUID();
    private static final RapidInsulinIobParameters IOB = new RapidInsulinIobParameters(4.5, 55.0);

    private final HovorkaGlucosePredictionService predictor = rawPredictor();

    /** a(t)=1 for [0,30) min after the anchor, else 0. */
    private static final ActivityProvider PULSE = t -> {
        long m = Duration.between(T0, t).toMinutes();
        return (m >= 0 && m < 30) ? 1.0 : 0.0;
    };
    /** a(t)=1 everywhere. */
    private static final ActivityProvider CONSTANT = t -> 1.0;

    @Test
    @DisplayName("with the NONE provider, predictions are identical to the un-modulated model")
    void noneProviderIsBitIdentical() {
        List<PredictionPointDTO> base = predict(8.0, List.of(), null, 120);
        List<PredictionPointDTO> none = predict(8.0, List.of(), ActivityProvider.NONE, 120);
        assertThat(none).hasSameSizeAs(base);
        for (int i = 0; i < base.size(); i++) {
            assertThat(none.get(i).getPredictedGlucose()).isEqualTo(base.get(i).getPredictedGlucose());
        }
    }

    @Test
    @DisplayName("activity lowers glucose even with no insulin on board (insulin-independent uptake)")
    void insulinIndependentDropAtLowIob() {
        List<PredictionPointDTO> none = predict(8.0, List.of(), ActivityProvider.NONE, 120);
        List<PredictionPointDTO> active = predict(8.0, List.of(), CONSTANT, 120);
        assertThat(glucoseAt(active, 60)).isLessThan(glucoseAt(none, 60));
    }

    @Test
    @DisplayName("an exercise pulse deepens the drop during it, and the effect persists after it ends")
    void extraDropDuringAndPersistentTailAfter() {
        // Modest bolus + higher start so neither trajectory floors — the tail is visible, not clipped.
        List<InsulinDose> bolus = List.of(InsulinDose.builder()
                .timestamp(T0).units(2.0).type(InsulinDose.InsulinType.BOLUS).build());

        List<PredictionPointDTO> none = predict(10.0, bolus, ActivityProvider.NONE, 240);
        List<PredictionPointDTO> pulsed = predict(10.0, bolus, PULSE, 240);

        double gapDuring = glucoseAt(none, 30) - glucoseAt(pulsed, 30);   // during the pulse
        double gapAfter  = glucoseAt(none, 120) - glucoseAt(pulsed, 120); // 90 min after it ended
        // The gap keeps widening after the pulse ends because insulin action stays amplified (tail).
        assertThat(gapDuring).isGreaterThan(0.0);         // deeper drop during exercise
        assertThat(gapAfter).isGreaterThan(gapDuring);    // effect persists & grows after exercise ends
    }

    @Test
    @DisplayName("sustained maximal activity keeps the ODE stable (finite, physiological glucose)")
    void stableUnderSustainedActivity() {
        List<InsulinDose> bigBolus = List.of(InsulinDose.builder()
                .timestamp(T0).units(10.0).type(InsulinDose.InsulinType.BOLUS).build());
        List<PredictionPointDTO> pts = predict(6.0, bigBolus, CONSTANT, 240);
        for (PredictionPointDTO pt : pts) {
            double g = pt.getPredictedGlucose();
            assertThat(Double.isFinite(g)).isTrue();
            assertThat(g).isGreaterThan(0.0).isLessThan(40.0);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<PredictionPointDTO> predict(double g0, List<InsulinDose> insulin,
                                             ActivityProvider provider, int horizon) {
        double w = 70.0;
        HovorkaParameters p = new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * w, HovorkaParameters.F01_PER_KG * w,
                HovorkaParameters.F01_PER_KG * w, HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 1.0, 2.0, w);
        if (provider == null) {
            return predictor.buildPredictionPath(p, IOB, null, g0, T0, List.of(), insulin, List.of(),
                    USER, horizon);
        }
        return predictor.buildPredictionPath(p, IOB, null, g0, T0, List.of(), insulin, List.of(),
                USER, horizon, provider);
    }

    private double glucoseAt(List<PredictionPointDTO> pts, int min) {
        LocalDateTime target = T0.plusMinutes(min);
        return pts.stream()
                .filter(pt -> pt.getTimestamp().equals(target))
                .findFirst().orElseThrow(() -> new AssertionError("no point at +" + min + "min"))
                .getPredictedGlucose();
    }

    private static HovorkaGlucosePredictionService rawPredictor() {
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaOdeSolver solver = new HovorkaOdeSolver(gut);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        return new HovorkaGlucosePredictionService(
                mock(HovorkaParameterService.class), solver, basal,
                mock(UserInsulinPreferencesService.class), gut,
                mock(UserSettingsService.class), PredictionResidualProvider.NONE);
    }
}
