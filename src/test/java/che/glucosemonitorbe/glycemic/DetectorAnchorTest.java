package che.glucosemonitorbe.glycemic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DetectorAnchorTest {

    private static final long T0 = 1_795_770_000_000L;

    private static GlycemicEventDetector.CgmPoint p(double minute, double mmol) {
        return new GlycemicEventDetector.CgmPoint(T0 + (long) (minute * 60_000), mmol);
    }

    @Test
    void reboundAfterAShowerDipDoesNotSetTheLevel() {
        // 2026-11-27 12:49 11.5 (rebound after the hot-shower dip), then 11.0, 10.4, 9.8, 9.8, 9.2.
        // Anchored on 11.5 the whole 4 h forecast sat ~1 mmol/L high and a "fall" was flagged.
        List<GlycemicEventDetector.CgmPoint> w = List.of(p(0, 11.5), p(1, 11.0), p(3, 10.4), p(7, 9.8),
                p(8, 9.8), p(13, 9.2), p(17, 9.5), p(22, 9.7));

        GlycemicEventDetector.CgmPoint a = GlycemicEventDetector.anchor(w);

        // Six readings in 15 min -> the first five; median 10.4 at the middle reading (12:52, really 10.4).
        assertThat(a).isEqualTo(p(3, 10.4));
    }

    @Test
    void steadyTrendIsNotBiased() {
        // A straight 0.1 mmol/L/min fall: the anchor lies on the line.
        List<GlycemicEventDetector.CgmPoint> w = new ArrayList<>();
        for (int m = 0; m <= 30; m += 5) w.add(p(m, 10.0 - 0.1 * m));

        GlycemicEventDetector.CgmPoint a = GlycemicEventDetector.anchor(w);

        double minute = (a.epochMs() - T0) / 60_000.0;
        assertThat(a.mmol()).isCloseTo(10.0 - 0.1 * minute, within(1e-9));
        assertThat((a.epochMs() - T0) % (5 * 60_000)).as("on the readings' grid").isZero();
    }

    @Test
    void singleReadingIsItsOwnAnchor() {
        assertThat(GlycemicEventDetector.anchor(List.of(p(0, 7.0), p(20, 8.0)))).isEqualTo(p(0, 7.0));
    }
}
