package che.glucosemonitorbe.hovorka.learning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the prediction-band uncertainty model: it should recover the residual spread per
 * horizon, widen monotonically, respect the sensor floor, and extrapolate sensibly past the last
 * trained horizon.
 */
class PredictionUncertaintyModelTest {

    private static final int N_PER_HORIZON = 800;

    /** Build samples whose residual (actual − predicted) is Gaussian with a known σ at each horizon. */
    private static List<AnchorSample> samplesWithSd(double[] sdPerHorizon, long seed) {
        Random rng = new Random(seed);
        List<AnchorSample> out = new ArrayList<>();
        int[] horizons = PredictionUncertaintyModel.HORIZONS;
        for (int i = 0; i < horizons.length; i++) {
            double predicted = 8.0;
            for (int j = 0; j < N_PER_HORIZON; j++) {
                double actual = predicted + rng.nextGaussian() * sdPerHorizon[i];
                out.add(new AnchorSample(horizons[i], predicted, actual, predicted, Regime.FASTING, 12));
            }
        }
        return out;
    }

    @Test
    void fit_recoversPerHorizonSigma() {
        double[] trueSd = {0.5, 1.0, 1.5, 2.0};
        PredictionUncertaintyModel m = PredictionUncertaintyModel.fit(samplesWithSd(trueSd, 42), ResidualBiasModel.neutral());

        assertThat(m.sdAtHorizon(30)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.2));
        assertThat(m.sdAtHorizon(60)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.2));
        assertThat(m.sdAtHorizon(120)).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.3));
    }

    @Test
    void band_widensMonotonicallyWithHorizon() {
        double[] trueSd = {0.5, 1.0, 1.5, 2.0};
        PredictionUncertaintyModel m = PredictionUncertaintyModel.fit(samplesWithSd(trueSd, 7), ResidualBiasModel.neutral());
        double s30 = m.sdAtHorizon(30), s60 = m.sdAtHorizon(60), s120 = m.sdAtHorizon(120), s240 = m.sdAtHorizon(240);
        assertThat(s30).isLessThanOrEqualTo(s60);
        assertThat(s60).isLessThanOrEqualTo(s120);
        // Beyond the last trained knot the band must keep widening (√-time extrapolation).
        assertThat(s240).isGreaterThan(s120);
        assertThat(s240).isLessThanOrEqualTo(PredictionUncertaintyModel.SD_MAX);
    }

    @Test
    void nonDecreasing_evenWhenRawSpreadDips() {
        // 90-min noisier than 120-min: the model must not let the band narrow at the later horizon.
        double[] trueSd = {0.5, 1.0, 2.5, 1.2};
        PredictionUncertaintyModel m = PredictionUncertaintyModel.fit(samplesWithSd(trueSd, 3), ResidualBiasModel.neutral());
        assertThat(m.sdAtHorizon(120)).isGreaterThanOrEqualTo(m.sdAtHorizon(90) - 1e-9);
    }

    @Test
    void floor_isRespectedForNearPerfectPredictions() {
        double[] trueSd = {0.0, 0.0, 0.0, 0.0};
        PredictionUncertaintyModel m = PredictionUncertaintyModel.fit(samplesWithSd(trueSd, 11), ResidualBiasModel.neutral());
        assertThat(m.sdAtHorizon(60)).isEqualTo(PredictionUncertaintyModel.SD_FLOOR);
        // Very short horizon collapses toward the sensor floor.
        assertThat(m.sdAtHorizon(1)).isEqualTo(PredictionUncertaintyModel.SD_FLOOR);
    }

    @Test
    void belowFirstKnot_rampsFromFloor() {
        double[] trueSd = {1.0, 1.5, 2.0, 2.5};
        PredictionUncertaintyModel m = PredictionUncertaintyModel.fit(samplesWithSd(trueSd, 5), ResidualBiasModel.neutral());
        double s15 = m.sdAtHorizon(15);
        assertThat(s15).isGreaterThan(PredictionUncertaintyModel.SD_FLOOR);
        assertThat(s15).isLessThan(m.sdAtHorizon(30));
    }

    @Test
    void emptySamples_fallBackToPopulationPrior() {
        PredictionUncertaintyModel m = PredictionUncertaintyModel.fit(List.of(), ResidualBiasModel.neutral());
        PredictionUncertaintyModel pop = PredictionUncertaintyModel.populationDefault();
        assertThat(m.sdAtHorizon(120)).isEqualTo(pop.sdAtHorizon(120));
    }

    @Test
    void populationDefault_isIncreasingAndSane() {
        PredictionUncertaintyModel m = PredictionUncertaintyModel.populationDefault();
        assertThat(m.sdAtHorizon(30)).isLessThan(m.sdAtHorizon(120));
        assertThat(m.sdAtHorizon(120)).isBetween(1.5, 3.0);
    }

    @Test
    void csv_roundTripsPreservingSigma() {
        double[] trueSd = {0.6, 1.1, 1.6, 2.1};
        PredictionUncertaintyModel m = PredictionUncertaintyModel.fit(samplesWithSd(trueSd, 99), ResidualBiasModel.neutral());
        PredictionUncertaintyModel back = PredictionUncertaintyModel.fromCsv(m.toCsv());
        for (int h : new int[]{30, 60, 90, 120}) {
            assertThat(back.sdAtHorizon(h)).isCloseTo(m.sdAtHorizon(h), org.assertj.core.data.Offset.offset(1e-3));
        }
    }

    @Test
    void fromCsv_returnsPopulationPriorOnGarbage() {
        assertThat(PredictionUncertaintyModel.fromCsv(null).sdAtHorizon(60))
                .isEqualTo(PredictionUncertaintyModel.populationDefault().sdAtHorizon(60));
        assertThat(PredictionUncertaintyModel.fromCsv("not,a,grid").sdAtHorizon(60))
                .isEqualTo(PredictionUncertaintyModel.populationDefault().sdAtHorizon(60));
    }
}
