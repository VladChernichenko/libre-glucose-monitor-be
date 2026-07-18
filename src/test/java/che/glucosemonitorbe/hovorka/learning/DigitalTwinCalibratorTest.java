package che.glucosemonitorbe.hovorka.learning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Tests the learning machinery against a synthetic user whose "true" parameters are known, so we
 * can assert the calibrator recovers them - and, crucially, that it stays close to the truth even
 * when the training data is contaminated with mis-logged (outlier) anchors.
 */
class DigitalTwinCalibratorTest {

    private static final double TRUE_ISF_SCALE = 1.30;
    private static final double TRUE_AG_SCALE  = 0.85;

    @Test
    void recoversKnownScalesFromCleanData() {
        AnchorSampleSource train = new SyntheticSource(240, 0.0, 1L);
        AnchorSampleSource val   = new SyntheticSource(120, 0.0, 2L);

        DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(train, val);

        assertThat(r.scales().isfScale()).isCloseTo(TRUE_ISF_SCALE, offset(0.08));
        assertThat(r.scales().agScale()).isCloseTo(TRUE_AG_SCALE, offset(0.08));
        assertThat(r.improved()).isTrue();
        assertThat(r.maeCalibrated()).isLessThan(r.maeBaseline());
    }

    @Test
    void staysNearTruthDespiteMisloggedOutliers() {
        // 5% of training anchors are corrupted by a one-sided +6 mmol/L unexplained rise
        // (e.g. a forgotten meal). Validation is clean.
        AnchorSampleSource train = new SyntheticSource(240, 0.05, 3L);
        AnchorSampleSource val   = new SyntheticSource(120, 0.0, 4L);

        DigitalTwinCalibrator.Result robust = new DigitalTwinCalibrator().calibrate(train, val);

        // The robust (Huber) fit still lands near the true ISF scale and improves out-of-sample.
        assertThat(robust.scales().isfScale()).isCloseTo(TRUE_ISF_SCALE, offset(0.15));
        assertThat(robust.improved()).isTrue();

        // A plain squared-loss fit on the same contaminated data is dragged noticeably further off:
        // Huber's estimate must be strictly closer to the truth.
        double squaredIsf = squaredLossFit(train);
        assertThat(Math.abs(robust.scales().isfScale() - TRUE_ISF_SCALE))
                .isLessThan(Math.abs(squaredIsf - TRUE_ISF_SCALE));
    }

    @Test
    void safelyDeclinesToApplyWhenDataIsTooContaminated() {
        // At 18% one-sided contamination the fit is biased enough that the calibrated model does
        // WORSE on the clean held-out window - the temporal-holdout gate must refuse to apply it.
        AnchorSampleSource train = new SyntheticSource(240, 0.18, 3L);
        AnchorSampleSource val   = new SyntheticSource(120, 0.0, 4L);

        DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(train, val);

        assertThat(r.improved()).isFalse();
        assertThat(r.status()).contains("no improvement");
    }

    @Test
    void reportsInsufficientDataWhenAnchorsAreTooFew() {
        AnchorSampleSource tiny = new SyntheticSource(6, 0.0, 5L);
        DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(tiny, tiny);
        assertThat(r.improved()).isFalse();
        assertThat(r.status()).contains("insufficient data");
    }

    // -- Plain (non-robust) least-squares reference fit --------------------------
    // Same LM optimiser as the calibrator but WITHOUT the IRLS/Huber re-weighting, so it shows how
    // far an un-robust squared-loss fit is dragged by the mis-logged outliers.

    private static double squaredLossFit(AnchorSampleSource train) {
        LmParameterFitter.ResidualModel model = p -> {
            List<AnchorSample> s = train.replay(TwinScales.of(p[0], p[1]));
            double[] r = new double[s.size() + 2];
            for (int i = 0; i < s.size(); i++) r[i] = s.get(i).error();
            r[s.size()]     = Math.sqrt(0.05) * (p[0] - 1.0);   // same ridge-to-1.0 as the calibrator
            r[s.size() + 1] = Math.sqrt(0.05) * (p[1] - 1.0);
            return r;
        };
        LmParameterFitter.Result res = new LmParameterFitter().fit(
                model,
                new double[]{1.0, 1.0},
                new double[]{TwinScales.MIN_SCALE, TwinScales.MIN_SCALE},
                new double[]{TwinScales.MAX_SCALE, TwinScales.MAX_SCALE});
        return TwinScales.clamp(res.params()[0]);
    }

    /**
     * A synthetic sample source. Each anchor is either "correction-like" (sensitive to the ISF
     * scale) or "meal-like" (sensitive to the meal-magnitude scale), which makes both scales
     * jointly identifiable. Predictions equal the clean actual when scales are at their true values;
     * a fraction of anchors are corrupted with a one-sided offset to emulate mis-logged data.
     */
    private static final class SyntheticSource implements AnchorSampleSource {
        private final double[] cleanActual;
        private final double[] noise;
        private final double[] aIsf;   // per-sample sensitivity to (isfScale − true)
        private final double[] aAg;    // per-sample sensitivity to (agScale − true)
        private final double[] outlier; // additive corruption on the actual (0 for clean)
        private final int[] hour;

        SyntheticSource(int n, double outlierFraction, long seed) {
            Random rnd = new Random(seed);
            cleanActual = new double[n];
            noise = new double[n];
            aIsf = new double[n];
            aAg = new double[n];
            outlier = new double[n];
            hour = new int[n];
            for (int i = 0; i < n; i++) {
                cleanActual[i] = 6.0 + 4.0 * rnd.nextDouble();
                noise[i] = 0.3 * rnd.nextGaussian();
                hour[i] = i % 24;
                if (i % 2 == 0) { aIsf[i] = -2.5; aAg[i] = 0.0; }   // correction-like
                else            { aIsf[i] = -0.5; aAg[i] = 3.0; }   // meal-like
                outlier[i] = rnd.nextDouble() < outlierFraction ? 6.0 : 0.0;
            }
        }

        @Override public int anchorCount() { return cleanActual.length; }

        @Override public List<AnchorSample> replay(TwinScales scales) {
            List<AnchorSample> out = new ArrayList<>(cleanActual.length);
            for (int i = 0; i < cleanActual.length; i++) {
                double predicted = cleanActual[i]
                        + aIsf[i] * (scales.isfScale() - TRUE_ISF_SCALE)
                        + aAg[i]  * (scales.agScale()  - TRUE_AG_SCALE)
                        + noise[i];
                double actual = cleanActual[i] + outlier[i];
                out.add(new AnchorSample(60, predicted, actual, cleanActual[i], Regime.MEAL, hour[i]));
            }
            return out;
        }
    }
}
