package che.glucosemonitorbe.glycemic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static che.glucosemonitorbe.glycemic.ResidualScannerTest.*;
import static org.assertj.core.api.Assertions.assertThat;

class OnsetLocatorTest {

    private final ResidualScanner scanner = new ResidualScanner(2.0, 45, 15, 24);

    @Test
    void backtracksFromThresholdCrossingToLastNearZeroPoint() {
        // sigma floor 0.3 -> near-zero band 0.15, threshold 0.6. Ramp starts at index 20.
        List<Double> ramp = List.of(0.1, 0.2, 0.4, 0.8, 1.2, 1.6, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0);
        // Noise, not flat zeros, around the ramp: a zero residual counts as positive and would glue the
        // baseline onto the run.
        ResidualSeries s = series(concat(noise(20), ramp, noise(10)));
        ResidualRun run = scanner.runs(s).get(0);
        assertThat(OnsetLocator.locate(s, run, 60)).isEqualTo(20);   // the 0.1 point (smoothed 0.1 < 0.15)
    }

    @Test
    void backtrackIsCappedAtTheLimit() {
        // A very slow ramp: no near-zero point within 15 min of the crossing -> earliest point examined.
        List<Double> slow = List.of(0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.7, 0.8, 0.9, 1.0,
                1.5, 1.5, 1.5, 1.5);
        ResidualSeries s = series(concat(noise(20), slow, noise(5)));
        ResidualRun run = scanner.runs(s).get(0);
        int onset = OnsetLocator.locate(s, run, 15);
        assertThat(s.epochMs(run.firstExceed()) - s.epochMs(onset)).isEqualTo(15 * 60_000L);
    }

    @Test
    void backtrackStopsWhereAnOppositeMoveEnds() {
        // a rise immediately followed by a fall: the fall's onset is its own first point, not mid-rise
        ResidualSeries s = series(concat(noise(20), flat(11, 3.0), flat(11, -3.0), noise(10)));
        ResidualRun fall = scanner.runs(s).get(1);
        assertThat(OnsetLocator.locate(s, fall, 60)).isEqualTo(fall.from());
    }

    @Test
    void runAtSeriesStartReturnsFirstIndex() {
        ResidualSeries s = series(concat(flat(12, 3.0), noise(20)));
        ResidualRun run = scanner.runs(s).get(0);
        assertThat(OnsetLocator.locate(s, run, 60)).isZero();
    }
}
