package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.hovorka.ActivityGains;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Tests the gated activity calibration stage against a synthetic user whose true activity gains are
 * known. Each anchor sits at a different delay after an activity bout, so the drop it shows depends
 * on both the sensitivity gain (amplitude) and the tail half-life (how fast that amplitude fades),
 * which makes the two separately identifiable.
 */
class ActivityCalibrationTest {

    private static final ActivityGains TRUE_GAINS = new ActivityGains(2.0, 240.0);
    private static final TwinScales FIXED = new TwinScales(1.2, 0.9, 1.0, 1.1);

    @Test
    void recoversGeneratingGains() {
        AnchorSampleSource train = new ActivitySource(240, TRUE_GAINS, 11L);
        AnchorSampleSource val   = new ActivitySource(120, TRUE_GAINS, 12L);

        DigitalTwinCalibrator.ActivityFit fit =
                new DigitalTwinCalibrator().fitActivity(train, val, FIXED, 8);

        assertThat(fit.gains().gainScale()).isCloseTo(2.0, offset(0.3));
        assertThat(fit.gains().tailHalfLifeMin()).isCloseTo(240.0, offset(40.0));
        assertThat(fit.applied()).isTrue();
        assertThat(fit.maeWith()).isLessThan(fit.maeWithout());
        assertThat(fit.activityCount()).isEqualTo(8);
    }

    @Test
    void fewerThan5Activities_defaults() {
        AnchorSampleSource train = new ActivitySource(240, TRUE_GAINS, 11L);
        AnchorSampleSource val   = new ActivitySource(120, TRUE_GAINS, 12L);

        DigitalTwinCalibrator.ActivityFit fit =
                new DigitalTwinCalibrator().fitActivity(train, val, FIXED, 4);

        assertThat(fit.gains()).isEqualTo(ActivityGains.NEUTRAL);
        assertThat(fit.applied()).isFalse();
        assertThat(fit.status()).isEqualTo("fewer than 5 activities");
    }

    @Test
    void noImprovement_notApplied() {
        // Noise-free: the gate is a strict MAE comparison, so with sensor noise a near-neutral fit can
        // "beat" NEUTRAL on validation by a hair. Without noise NEUTRAL is already exact and the fit
        // has nothing to gain.
        AnchorSampleSource train = new ActivitySource(240, ActivityGains.NEUTRAL, 21L, 0.0);
        AnchorSampleSource val   = new ActivitySource(120, ActivityGains.NEUTRAL, 22L, 0.0);

        DigitalTwinCalibrator.ActivityFit fit =
                new DigitalTwinCalibrator().fitActivity(train, val, FIXED, 8);

        assertThat(fit.applied()).isFalse();
        assertThat(fit.maeWith()).isGreaterThanOrEqualTo(fit.maeWithout());
    }

    @Test
    void emptyValidation_notApplied() {
        AnchorSampleSource train = new ActivitySource(240, TRUE_GAINS, 11L);
        AnchorSampleSource val   = new ActivitySource(0, TRUE_GAINS, 12L);

        DigitalTwinCalibrator.ActivityFit fit =
                new DigitalTwinCalibrator().fitActivity(train, val, FIXED, 8);

        assertThat(fit.applied()).isFalse();
        assertThat(fit.gains()).isEqualTo(ActivityGains.NEUTRAL);
        assertThat(fit.status()).isEqualTo("too little validation data");
    }

    @Test
    void existingScalesUntouched() {
        ActivitySource train = new ActivitySource(240, TRUE_GAINS, 11L);
        ActivitySource val   = new ActivitySource(120, TRUE_GAINS, 12L);

        new DigitalTwinCalibrator().fitActivity(train, val, FIXED, 8);

        assertThat(train.seenScales).isNotEmpty().allMatch(s -> s == FIXED);
        assertThat(val.seenScales).isNotEmpty().allMatch(s -> s == FIXED);
    }

    /**
     * Synthetic source: every sample is taken {@code delay} minutes after an activity bout. The model
     * predicts a drop of {@code DROP * gainScale * 2^(-delay / halfLife)} on top of a base trace; the
     * "actual" is the same expression under the generating gains plus sensor noise. Delays span
     * 15-300 min so amplitude and decay rate are both visible in the data.
     */
    private static final class ActivitySource implements AnchorSampleSource {
        private static final double DROP = 1.5;   // mmol/L drop at neutral gain, zero delay

        private final double[] base;
        private final double[] noise;
        private final int[] delay;
        private final ActivityGains truth;
        final List<TwinScales> seenScales = new ArrayList<>();

        ActivitySource(int n, ActivityGains truth, long seed) {
            this(n, truth, seed, 0.2);
        }

        ActivitySource(int n, ActivityGains truth, long seed, double noiseSd) {
            Random rnd = new Random(seed);
            this.truth = truth;
            base = new double[n];
            noise = new double[n];
            delay = new int[n];
            for (int i = 0; i < n; i++) {
                base[i] = 6.0 + 4.0 * rnd.nextDouble();
                noise[i] = noiseSd * rnd.nextGaussian();
                delay[i] = 15 + 15 * (i % 20);   // 15..300 min after activity
            }
        }

        private static double drop(ActivityGains g, int delayMin) {
            return DROP * g.gainScale() * Math.pow(0.5, delayMin / g.tailHalfLifeMin());
        }

        @Override public int anchorCount() { return base.length; }

        @Override public List<AnchorSample> replay(TwinScales scales) {
            return replay(scales, ActivityGains.NEUTRAL);
        }

        @Override public List<AnchorSample> replay(TwinScales scales, ActivityGains gains) {
            seenScales.add(scales);
            List<AnchorSample> out = new ArrayList<>(base.length);
            for (int i = 0; i < base.length; i++) {
                double predicted = base[i] - drop(gains, delay[i]);
                double actual = base[i] - drop(truth, delay[i]) + noise[i];
                out.add(new AnchorSample(delay[i], predicted, actual, base[i], Regime.MEAL, i % 24));
            }
            return out;
        }
    }
}
