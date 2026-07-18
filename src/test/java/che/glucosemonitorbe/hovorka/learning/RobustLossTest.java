package che.glucosemonitorbe.hovorka.learning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class RobustLossTest {

    @Test
    void huberIsQuadraticBelowDeltaAndLinearAbove() {
        double delta = 2.0;
        // Small residual -> 0.5 r²
        assertThat(RobustLoss.huber(1.0, delta)).isCloseTo(0.5, offset(1e-9));
        // Large residual -> linear: delta*(|r| − 0.5*delta)
        assertThat(RobustLoss.huber(10.0, delta)).isCloseTo(2.0 * (10.0 - 1.0), offset(1e-9));
        // Symmetry
        assertThat(RobustLoss.huber(-10.0, delta)).isEqualTo(RobustLoss.huber(10.0, delta));
    }

    @Test
    void aSingleOutlierDominatesSquaredLossFarMoreThanHuberLoss() {
        // Bulk of clean samples plus one gross outlier.
        List<AnchorSample> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) samples.add(sample(0.2)); // small errors
        samples.add(sample(15.0));                             // one mis-logged anchor

        double meanHuber = RobustLoss.meanHuber(samples, 2.0);
        // Squared-loss equivalent for comparison.
        double meanSq = samples.stream().mapToDouble(s -> 0.5 * s.error() * s.error()).average().orElse(0);

        // The outlier's contribution is capped under Huber, so the mean stays far smaller.
        assertThat(meanHuber).isLessThan(meanSq / 3.0);
    }

    @Test
    void ridgePenalisesDistanceFromOne() {
        assertThat(RobustLoss.ridgeToOne(1.0, 1.0, 1.0)).isCloseTo(0.0, offset(1e-9));
        assertThat(RobustLoss.ridgeToOne(1.0, 1.5, 0.5)).isCloseTo(0.25 + 0.25, offset(1e-9));
        assertThat(RobustLoss.ridgeToOne(0.0, 2.0)).isEqualTo(0.0); // disabled
    }

    private static AnchorSample sample(double error) {
        // actual fixed at 7.0; predicted = actual + error.
        return new AnchorSample(60, 7.0 + error, 7.0, 7.0, Regime.MEAL, 12);
    }
}
