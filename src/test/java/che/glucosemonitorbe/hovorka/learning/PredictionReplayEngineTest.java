package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plumbing test: the replay engine drives the real Hovorka ODE over a synthetic CGM trace and
 * produces finite, in-range comparison samples. Validates the engine ↔ predictor wiring end-to-end
 * (not numerical accuracy - that is the calibrator's concern).
 */
class PredictionReplayEngineTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    private static final long T0 = 1_700_000_000_000L; // fixed epoch

    @Test
    void producesFiniteInRangeSamplesFromASyntheticTrace() {
        HovorkaGlucosePredictionService predictor = rawPredictor();
        HovorkaParameters params = params70kg();
        RapidInsulinIobParameters rapidIob = new RapidInsulinIobParameters(4.5, 55.0);

        // 8 h of CGM every 5 min, gently oscillating around 7 mmol/L.
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (int m = 0; m <= 480; m += 5) {
            double mmol = 7.0 + 0.8 * Math.sin(m / 60.0);
            cgm.add(new PredictionReplayEngine.Reading(T0 + m * 60_000L, mmol));
        }
        // One 40 g meal + 5 U bolus at t0+60 min.
        List<PredictionReplayEngine.Event> events = List.of(
                new PredictionReplayEngine.Event(T0 + 60 * 60_000L, 40.0, 5.0, false, 10.0, 8.0, 3.0));

        PredictionReplayEngine engine = new PredictionReplayEngine(
                predictor, params, rapidIob, /*settings*/ null, USER, cgm, events,
                new PredictionReplayEngine.Config());

        assertThat(engine.anchorCount()).isGreaterThan(0);

        List<AnchorSample> samples = engine.replay(TwinScales.neutral());
        assertThat(samples).isNotEmpty();
        for (AnchorSample s : samples) {
            assertThat(s.predicted()).isFinite();
            assertThat(s.predicted()).isBetween(1.0, 25.0);
            assertThat(s.actual()).isFinite();
            assertThat(s.hourOfDay()).isBetween(0, 23);
        }
    }

    private static HovorkaGlucosePredictionService rawPredictor() {
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaOdeSolver solver = new HovorkaOdeSolver(gut);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        // The pre-fetched overload used by the engine never touches these collaborators.
        return new HovorkaGlucosePredictionService(
                mock(che.glucosemonitorbe.hovorka.HovorkaParameterService.class),
                solver, basal,
                mock(che.glucosemonitorbe.service.UserInsulinPreferencesService.class),
                gut,
                mock(che.glucosemonitorbe.service.UserSettingsService.class),
                PredictionResidualProvider.NONE);
    }

    private static HovorkaParameters params70kg() {
        double w = 70.0;
        double vG = HovorkaParameters.VG_PER_KG * w;
        double f01 = HovorkaParameters.F01_PER_KG * w;
        return new HovorkaParameters(vG, f01, f01,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 1.0, 2.2, w);
    }
}
