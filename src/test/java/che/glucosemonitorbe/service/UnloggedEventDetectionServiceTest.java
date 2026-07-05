package che.glucosemonitorbe.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-math unit tests for the detector's robust spread estimate (no DB/predictor needed). */
class UnloggedEventDetectionServiceTest {

    @Test
    @DisplayName("robustScale is the MAD-based spread, floored at 0.3 and robust to a minority of outliers")
    void robustScaleIsMadFloored() {
        // All-equal residuals → MAD 0 → floored to 0.3.
        assertThat(UnloggedEventDetectionService.robustScale(List.of(1.0, 1.0, 1.0, 1.0)))
                .isEqualTo(0.3);

        // A minority of large outliers must not blow up the scale (median/MAD are robust): most
        // residuals are ~0, so the spread stays near the floor despite the +6 spikes.
        List<Double> withOutliers = List.of(0.0, 0.1, -0.1, 0.0, 6.0, 6.0, 0.0, -0.1, 0.1, 0.0);
        assertThat(UnloggedEventDetectionService.robustScale(withOutliers)).isLessThan(0.5);

        // A genuinely spread-out sample yields a scale well above the floor.
        assertThat(UnloggedEventDetectionService.robustScale(List.of(-3.0, -1.0, 1.0, 3.0)))
                .isGreaterThan(1.0);
    }

    @Test
    @DisplayName("adaptive threshold: the same sustained run flags a low-σ user but not a high-σ user")
    void thresholdAdaptsToUserSigma() {
        // A sustained +4 mmol run over 60 min (13 points at 5-min spacing).
        List<long[]> t = new ArrayList<>();
        List<Double> r = new ArrayList<>();
        for (int i = 0; i < 13; i++) { t.add(new long[]{i * 5L * 60_000L}); r.add(4.0); }
        int persistence = 45;

        // robustScale turns each user's own residual spread into σ: quiet baseline → floor 0.3;
        // noisy baseline → a large σ. (This is what makes the threshold per-user adaptive.)
        double quietSigma = UnloggedEventDetectionService.robustScale(List.of(0.0, 0.1, -0.1, 0.0));
        double noisySigma = UnloggedEventDetectionService.robustScale(List.of(-4.0, -4.0, 4.0, 4.0));
        assertThat(quietSigma).isLessThan(noisySigma);

        // Same run: flags at the low-σ user's threshold (2·0.3), not at the high-σ user's (2·~4).
        assertThat(UnloggedEventDetectionService.strongestRun(t, r, 2.0 * quietSigma, persistence)).isNotNull();
        assertThat(UnloggedEventDetectionService.strongestRun(t, r, 2.0 * noisySigma, persistence)).isNull();
    }

    @Test
    @DisplayName("a run shorter than the persistence minimum never qualifies, regardless of size")
    void shortRunRejectedByPersistence() {
        // A big +8 mmol run but only 10 min long (< 45 min persistence).
        List<long[]> t = new ArrayList<>();
        List<Double> r = new ArrayList<>();
        for (int i = 0; i < 3; i++) { t.add(new long[]{i * 5L * 60_000L}); r.add(8.0); }
        assertThat(UnloggedEventDetectionService.strongestRun(t, r, 1.0, 45)).isNull();
    }
}
