package che.glucosemonitorbe.hovorka.learning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ResidualBiasModelTest {

    @Test
    void learnsAStrongConsistentHourlyBias() {
        // At 06:00 the model consistently under-predicts by ~3 mmol/L (dawn phenomenon).
        List<AnchorSample> samples = new ArrayList<>();
        for (int i = 0; i < 40; i++) samples.add(sampleAt(6, 8.0, 11.0));  // actual 11, pred 8
        // Every other hour is spot-on.
        for (int h = 0; h < 24; h++) {
            if (h == 6) continue;
            for (int i = 0; i < 20; i++) samples.add(sampleAt(h, 7.0, 7.0));
        }

        ResidualBiasModel model = ResidualBiasModel.fit(samples);

        // The 06:00 correction should be strongly positive (raise the prediction toward reality),
        // clamped to the safety rail but well above other hours.
        assertThat(model.correctionAt(6)).isGreaterThan(1.5);
        assertThat(model.correctionAt(12)).isCloseTo(0.0, offset(0.3));
    }

    @Test
    void shrinksSparseNoisyHoursTowardTheGlobalMean() {
        // One hour has a single extreme sample; shrinkage must stop it becoming the raw value.
        List<AnchorSample> samples = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            for (int i = 0; i < 30; i++) samples.add(sampleAt(h, 7.0, 7.0)); // global ≈ 0
        }
        samples.add(sampleAt(3, 7.0, 15.0)); // lone +8 spike at 03:00

        ResidualBiasModel model = ResidualBiasModel.fit(samples);
        // Raw mean at 03:00 would be ~+0.26; with a global mean of ~0 and shrinkage it stays small.
        assertThat(Math.abs(model.correctionAt(3))).isLessThan(0.3);
    }

    @Test
    void csvRoundTripsAndNeutralIsAllZero() {
        List<AnchorSample> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) samples.add(sampleAt(9, 6.0, 8.0));
        ResidualBiasModel model = ResidualBiasModel.fit(samples);

        ResidualBiasModel restored = ResidualBiasModel.fromCsv(model.toCsv());
        assertThat(restored.correctionAt(9)).isCloseTo(model.correctionAt(9), offset(1e-3));

        assertThat(ResidualBiasModel.neutral().isNeutral()).isTrue();
        assertThat(ResidualBiasModel.fromCsv("garbage").isNeutral()).isTrue();
    }

    private static AnchorSample sampleAt(int hour, double predicted, double actual) {
        return new AnchorSample(60, predicted, actual, predicted, Regime.MEAL, hour);
    }
}
