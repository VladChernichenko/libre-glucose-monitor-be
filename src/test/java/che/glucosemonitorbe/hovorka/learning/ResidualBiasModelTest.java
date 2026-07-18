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

    @Test
    void interpolatesSmoothlyAcrossTheHourInsteadOfStepping() {
        // Craft a model with a sharp +2 mmol/L bias at hour 6 and 0 at neighbouring hours 5 and 7.
        List<AnchorSample> samples = new ArrayList<>();
        for (int i = 0; i < 40; i++) samples.add(sampleAt(6, 8.0, 10.0)); // under-predict by 2 at 06:00
        for (int h = 0; h < 24; h++) {
            if (h == 6) continue;
            for (int i = 0; i < 40; i++) samples.add(sampleAt(h, 7.0, 7.0));
        }
        ResidualBiasModel model = ResidualBiasModel.fit(samples);

        // The interpolated value at a bucket centre (:30) equals the stepped value.
        assertThat(model.correctionAt(6, 30)).isCloseTo(model.correctionAt(6), offset(1e-9));

        // Across the 06:00 boundary the value must be continuous: minute 05:59 and 06:00 differ
        // only marginally, and both sit roughly halfway between the hour-5 and hour-6 corrections.
        double before = model.correctionAt(5, 59);
        double after  = model.correctionAt(6, 0);
        double midpoint = (model.correctionAt(5) + model.correctionAt(6)) / 2.0;
        assertThat(after).isCloseTo(midpoint, offset(1e-9));
        assertThat(Math.abs(after - before)).isLessThan(0.05); // no vertical step at :00

        // Monotone ramp from the low neighbour (hour 5) up to the peak centre (06:30).
        assertThat(model.correctionAt(6, 0)).isLessThan(model.correctionAt(6, 15));
        assertThat(model.correctionAt(6, 15)).isLessThan(model.correctionAt(6, 30));

        // Midnight wrap: hour 0 blends with hour 23, hour 23 blends with hour 0 — no exception.
        assertThat(model.correctionAt(0, 0)).isFinite();
        assertThat(model.correctionAt(23, 59)).isFinite();
    }

    private static AnchorSample sampleAt(int hour, double predicted, double actual) {
        return new AnchorSample(60, predicted, actual, predicted, Regime.MEAL, hour);
    }
}
