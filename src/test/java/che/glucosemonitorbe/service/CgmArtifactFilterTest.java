package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CgmArtifactFilterTest {

    private static final long T0 = 1_795_766_000_000L;

    /** A reading {@code minute} minutes after T0 at {@code mmol}. */
    private static CgmReading r(int minute, double mmol) {
        CgmReading c = new CgmReading();
        c.setDateTimestamp(T0 + minute * 60_000L);
        c.setSgv((int) Math.round(mmol * 18.018));
        return c;
    }

    private static List<Double> mmol(List<CgmReading> rs) {
        return rs.stream().map(c -> Math.round(c.getSgv() / 18.018 * 10) / 10.0).toList();
    }

    @Test
    void hotShowerDip_2026_11_27_isRemoved() {
        // 12:16 10.4, 12:21 10.9, 12:26 7.8, 12:35 9.7, 12:41 10.5 - as stored.
        List<CgmReading> in = List.of(r(0, 10.4), r(5, 10.9), r(10, 7.8), r(19, 9.7), r(25, 10.5));
        assertThat(mmol(CgmArtifactFilter.clean(in))).containsExactly(10.4, 10.9, 10.5);
    }

    @Test
    void realFastDrop_thatDoesNotComeBack_isKept() {
        // A genuine fall: steep but it stays down - nothing to call an artifact.
        List<CgmReading> in = List.of(r(0, 10.9), r(5, 9.0), r(10, 7.8), r(15, 7.0), r(20, 6.6), r(40, 6.0));
        assertThat(CgmArtifactFilter.clean(in)).hasSize(6);
    }

    @Test
    void fastJumpThenSlowDecline_isKept() {
        // 2026-11-27 04:27 13.6 -> 15.4 -> 15.2 -> 14.9 -> 14.5: a real peak easing off, not a glitch.
        List<CgmReading> in = List.of(r(0, 13.6), r(5, 15.4), r(10, 15.2), r(15, 14.9), r(20, 14.5), r(25, 14.0));
        assertThat(CgmArtifactFilter.clean(in)).hasSize(6);
    }

    @Test
    void compressionLowAtNight_vShape_isRemoved() {
        // 2026-10-27 01:48 13.9 -> 11.7 -> 12.4 -> 13.3: a sharp V both ways.
        List<CgmReading> in = List.of(r(0, 13.9), r(5, 11.7), r(10, 12.4), r(15, 13.3));
        assertThat(mmol(CgmArtifactFilter.clean(in))).containsExactly(13.9, 13.3);
    }

    @Test
    void ordinaryNoise_isKept() {
        // 0.5 mmol jitter is below the size threshold even if it is quick.
        List<CgmReading> in = List.of(r(0, 8.0), r(5, 7.5), r(10, 8.1), r(15, 7.6), r(20, 8.0));
        assertThat(CgmArtifactFilter.clean(in)).hasSize(5);
    }

    @Test
    void briefSpikeUp_thatComesBack_isRemoved() {
        List<CgmReading> in = List.of(r(0, 6.0), r(5, 9.5), r(10, 6.2), r(15, 6.1));
        assertThat(mmol(CgmArtifactFilter.clean(in))).containsExactly(6.0, 6.2, 6.1);
    }

    @Test
    void steepButSlowEnoughMealRise_isKept() {
        // 0.2 mmol/L/min is physiologically possible (fast carbs) and must stay.
        List<CgmReading> in = new ArrayList<>();
        for (int m = 0; m <= 30; m += 5) in.add(r(m, 5.0 + 0.2 * m));
        assertThat(CgmArtifactFilter.clean(in)).hasSize(in.size());
    }

    @Test
    void dropAcrossAGap_isNotJudgedFast() {
        // 3 mmol over 20 min (a gap) is 0.15/min: plausible, kept.
        List<CgmReading> in = List.of(r(0, 10.0), r(20, 7.0), r(25, 9.8));
        assertThat(CgmArtifactFilter.clean(in)).hasSize(3);
    }

    @Test
    void unsortedAndNullValues_areHandled() {
        CgmReading broken = new CgmReading();
        List<CgmReading> in = new ArrayList<>(List.of(r(19, 9.7), r(5, 10.9), r(0, 10.4), r(25, 10.5), r(10, 7.8)));
        in.add(broken);
        assertThat(mmol(CgmArtifactFilter.clean(in))).containsExactly(10.4, 10.9, 10.5);
        assertThat(CgmArtifactFilter.clean(List.of())).isEmpty();
        assertThat(CgmArtifactFilter.clean(null)).isEmpty();
    }
}
