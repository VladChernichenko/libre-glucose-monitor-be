package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResidualScannerTest {

    private static final long STEP = 5 * 60_000L;
    private final ResidualScanner scanner = new ResidualScanner(2.0, 45, 15, 24);

    /** Builds points 5 min apart; residual = value (predicted 0). A NaN entry inserts a 20-min gap instead. */
    static ResidualSeries series(List<Double> residuals) {
        List<ResidualPoint> pts = new ArrayList<>();
        long t = 0;
        for (Double r : residuals) {
            if (r.isNaN()) { t += 3 * STEP; continue; }   // 20 min between neighbours instead of 5
            pts.add(new ResidualPoint(t, r, 0.0));
            t += STEP;
        }
        return ResidualScanner.series(pts);
    }

    static List<Double> noise(int n) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(i % 2 == 0 ? 0.1 : -0.1);
        return out;
    }

    static List<Double> flat(int n, double v) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(v);
        return out;
    }

    @SafeVarargs
    static List<Double> concat(List<Double>... parts) {
        List<Double> out = new ArrayList<>();
        for (List<Double> p : parts) out.addAll(p);
        return out;
    }

    @Test
    void returnsEveryQualifyingRunNotJustTheStrongest() {
        ResidualSeries s = series(concat(noise(20), flat(10, 3.0), noise(20), flat(10, -3.0), noise(10)));
        List<ResidualRun> runs = scanner.runs(s);
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).direction()).isEqualTo(Direction.RISE);
        assertThat(runs.get(1).direction()).isEqualTo(Direction.FALL);
    }

    @Test
    void oneOppositeSignReadingDoesNotSplitARun() {
        // Unsmoothed this is a 20-min and a 25-min run (both rejected); median-3 heals the blip.
        ResidualSeries s = series(concat(noise(20), flat(5, 3.0), List.of(-3.0), flat(6, 3.0), noise(20)));
        List<ResidualRun> runs = scanner.runs(s);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).durationMinutes()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void aCgmGapLongerThanTheLimitSplitsARun() {
        // 25 min + 20-min gap + 25 min would pass persistence as one run; split, neither half does.
        ResidualSeries s = series(concat(noise(20), flat(6, 3.0), List.of(Double.NaN), flat(6, 3.0), noise(20)));
        assertThat(scanner.runs(s)).isEmpty();
    }

    @Test
    void tooFewPointsYieldNothing() {
        assertThat(scanner.runs(series(flat(10, 5.0)))).isEmpty();
    }

    @Test
    void firstExceedIsTheFirstPointPastThreshold() {
        List<Double> ramp = List.of(0.1, 0.2, 0.4, 0.8, 1.2, 1.6, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0);
        ResidualSeries s = series(concat(noise(20), ramp, noise(10)));
        ResidualRun run = scanner.runs(s).get(0);
        // sigma floors at 0.3 -> threshold 0.6; the first smoothed value >= 0.6 is 0.8 (index 20 + 3)
        assertThat(run.firstExceed()).isEqualTo(23);
    }

    @Test
    void robustScaleIsMadFloored() {
        assertThat(ResidualScanner.robustScale(List.of(0.0, 0.0, 0.0, 0.0))).isEqualTo(0.3);
        assertThat(ResidualScanner.robustScale(List.of(1.0, 2.0, 3.0, 4.0, 5.0))).isCloseTo(1.4826, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void strongestPicksLargestAbsoluteMean() {
        ResidualSeries s = series(concat(noise(20), flat(10, 3.0), noise(20), flat(10, -4.0), noise(10)));
        assertThat(ResidualScanner.strongest(scanner.runs(s)).orElseThrow().direction()).isEqualTo(Direction.FALL);
    }
}
