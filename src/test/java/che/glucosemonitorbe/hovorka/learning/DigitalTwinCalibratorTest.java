package che.glucosemonitorbe.hovorka.learning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
    void thePriorWeighsTheSameHoweverManySamplesThereAre() {
        // The prior row is (σ_r / priorSd) × √samplesPerAnchor × (scale − 1): it follows the
        // residual spread, so it is in the residuals' own units, and it is not a fixed constant
        // that hundreds of error rows drown out.
        DigitalTwinCalibrator calibrator = new DigitalTwinCalibrator();
        assertThat(calibrator.priorRowWeight(2.0)).isCloseTo(2.0 / 0.3 * 2.0, within(1e-9));
        assertThat(calibrator.priorRowWeight(1.0)).isCloseTo(1.0 / 0.3 * 2.0, within(1e-9));

        DigitalTwinCalibrator.Config off = new DigitalTwinCalibrator.Config();
        off.priorSd = 0.0;
        assertThat(new DigitalTwinCalibrator(off).priorRowWeight(2.0)).isZero();
    }

    @Test
    void reportsInsufficientDataWhenAnchorsAreTooFew() {
        AnchorSampleSource tiny = new SyntheticSource(6, 0.0, 5L);
        DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(tiny, tiny);
        assertThat(r.improved()).isFalse();
        assertThat(r.status()).contains("insufficient data");
    }

    @Test
    void mealsWithUnloggedCarbsDoNotMoveTheIsfScale() {
        // Every meal-like anchor rises 3 mmol/L more than the log explains (carbs never logged).
        // ISF is read from the corrections alone, so it still lands on the truth.
        AnchorSampleSource train = new SyntheticSource(240, 0.0, 6L, TRUE_ISF_SCALE, 3.0, true);
        AnchorSampleSource val   = new SyntheticSource(120, 0.0, 7L, TRUE_ISF_SCALE, 3.0, true);

        DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(train, val);

        assertThat(r.scales().isfScale()).isCloseTo(TRUE_ISF_SCALE, offset(0.08));
    }

    @Test
    void leavesIsfNeutralWithoutEnoughTrustedCorrections() {
        // No anchor is a trusted correction: there is nothing sensitivity can be read from.
        AnchorSampleSource train = new SyntheticSource(240, 0.0, 8L, TRUE_ISF_SCALE, 0.0, false);
        AnchorSampleSource val   = new SyntheticSource(120, 0.0, 9L, TRUE_ISF_SCALE, 0.0, false);

        DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(train, val);

        assertThat(r.scales().isfScale()).isEqualTo(1.0);
    }

    @Test
    void neverAppliesAFitPinnedAtAClampBound() {
        // The data wants an ISF scale of 0.2, below the 0.5 clamp: the fit stops on the bound.
        AnchorSampleSource train = new SyntheticSource(240, 0.0, 10L, 0.2, 0.0, true);
        AnchorSampleSource val   = new SyntheticSource(120, 0.0, 11L, 0.2, 0.0, true);

        DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(train, val);

        assertThat(r.scales().isfScale()).isCloseTo(TwinScales.MIN_SCALE, offset(1e-3));
        assertThat(r.maeCalibrated()).isLessThan(r.maeBaseline());   // it would have passed the MAE gate
        assertThat(r.improved()).isFalse();
        assertThat(r.status()).contains("isfScale pinned at bound");
    }

    @Test
    void namesTheScaleRestingOnABound() {
        assertThat(DigitalTwinCalibrator.pinnedScale(new TwinScales(1.2, 0.9, 1.0, 1.05))).isNull();
        assertThat(DigitalTwinCalibrator.pinnedScale(new TwinScales(0.5, 0.9, 1.0, 1.0))).isEqualTo("isfScale");
        assertThat(DigitalTwinCalibrator.pinnedScale(new TwinScales(1.2, 2.0, 1.0, 1.0))).isEqualTo("agScale");
        assertThat(DigitalTwinCalibrator.pinnedScale(new TwinScales(1.2, 0.9, 1.0, 0.5))).isEqualTo("egpScale");
        // tMaxGScale is never fitted, so its neutral value is not a pinned one even at a bound.
        assertThat(DigitalTwinCalibrator.pinnedScale(new TwinScales(1.2, 0.9, 0.5, 1.0))).isNull();
    }

    // -- Insulin curve stage: [peakScale, diaScale] on corrections, on top of fixed scales --------

    @Test
    void recoversAPersonalInsulinCurveFromCorrections() {
        DigitalTwinCalibrator.CurveFit fit = new DigitalTwinCalibrator().fitInsulinCurve(
                new CurveSource(240, 0.8, 1.2, 12L), new CurveSource(120, 0.8, 1.2, 13L),
                TwinScales.neutral(), "FIASP", 20);

        assertThat(fit.scale().peakScale()).isCloseTo(0.8, offset(0.06));
        assertThat(fit.scale().diaScale()).isCloseTo(1.2, offset(0.06));
        assertThat(fit.applied()).isTrue();
        assertThat(fit.maeWith()).isLessThan(fit.maeWithout());
    }

    @Test
    void leavesTheCatalogCurveWhenItAlreadyFits() {
        DigitalTwinCalibrator.CurveFit fit = new DigitalTwinCalibrator().fitInsulinCurve(
                new CurveSource(240, 1.0, 1.0, 14L), new CurveSource(120, 1.0, 1.0, 15L),
                TwinScales.neutral(), "FIASP", 20);

        assertThat(fit.applied()).isFalse();
        assertThat(fit.status()).contains("not applied");
    }

    @Test
    void doesNotFitACurveFromTooFewCorrections() {
        DigitalTwinCalibrator.CurveFit fit = new DigitalTwinCalibrator().fitInsulinCurve(
                new CurveSource(240, 0.8, 1.2, 16L), new CurveSource(120, 0.8, 1.2, 17L),
                TwinScales.neutral(), "FIASP", DigitalTwinCalibrator.MIN_CURVE_DOSES - 1);

        assertThat(fit.applied()).isFalse();
        assertThat(fit.scale()).isEqualTo(CurveScale.NEUTRAL);
    }

    @Test
    void neverAppliesACurvePinnedAtABound() {
        // The data wants a peak at 0.4x the catalog value, below the 0.6 bound.
        DigitalTwinCalibrator.CurveFit fit = new DigitalTwinCalibrator().fitInsulinCurve(
                new CurveSource(240, 0.4, 1.0, 18L), new CurveSource(120, 0.4, 1.0, 19L),
                TwinScales.neutral(), "FIASP", 20);

        assertThat(fit.scale().peakScale()).isCloseTo(CurveScale.MIN_PEAK_SCALE, offset(1e-3));
        assertThat(fit.applied()).isFalse();
        assertThat(fit.status()).contains("pinned at bound");
    }

    /** Correction-only samples whose prediction error is linear in the distance from a true curve. */
    private static final class CurveSource implements AnchorSampleSource {
        private final int n;
        private final double truePeak, trueDia;
        private final double[] base, noise, aPeak, aDia;

        CurveSource(int n, double truePeak, double trueDia, long seed) {
            this.n = n; this.truePeak = truePeak; this.trueDia = trueDia;
            Random rnd = new Random(seed);
            base = new double[n]; noise = new double[n]; aPeak = new double[n]; aDia = new double[n];
            for (int i = 0; i < n; i++) {
                base[i] = 6.0 + 4.0 * rnd.nextDouble();
                noise[i] = 0.2 * rnd.nextGaussian();
                // Early horizons feel the peak, late ones the tail.
                if (i % 2 == 0) { aPeak[i] = 3.0; aDia[i] = 0.5; } else { aPeak[i] = 0.5; aDia[i] = 3.0; }
            }
        }

        @Override public int anchorCount() { return n; }

        @Override public List<AnchorSample> replay(TwinScales scales) {
            return replay(scales, che.glucosemonitorbe.hovorka.ActivityGains.NEUTRAL, "FIASP", CurveScale.NEUTRAL);
        }

        @Override public List<AnchorSample> replay(TwinScales scales, che.glucosemonitorbe.hovorka.ActivityGains gains,
                                                   String insulinCode, CurveScale curve) {
            List<AnchorSample> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double predicted = base[i] + aPeak[i] * (curve.peakScale() - truePeak)
                        + aDia[i] * (curve.diaScale() - trueDia) + noise[i];
                out.add(new AnchorSample(i % 2 == 0 ? 60 : 240, predicted, base[i], base[i],
                        Regime.CORRECTION, i % 24));
            }
            return out;
        }
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
        private final Regime[] regime;
        private final double trueIsf;

        SyntheticSource(int n, double outlierFraction, long seed) {
            this(n, outlierFraction, seed, TRUE_ISF_SCALE, 0.0, true);
        }

        /**
         * @param trueIsf            the ISF scale at which predictions match the clean actual
         * @param unloggedMealRise   extra rise [mmol/L] on every meal-like anchor's actual
         * @param trustedCorrections whether the correction-like anchors are tagged CORRECTION
         *                           (otherwise INSULIN_ONLY: insulin without carbs, but not trusted)
         */
        SyntheticSource(int n, double outlierFraction, long seed, double trueIsf,
                        double unloggedMealRise, boolean trustedCorrections) {
            this.trueIsf = trueIsf;
            regime = new Regime[n];
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
                if (i % 2 == 0) {                                    // correction-like
                    aIsf[i] = -2.5; aAg[i] = 0.0;
                    regime[i] = trustedCorrections ? Regime.CORRECTION : Regime.INSULIN_ONLY;
                } else {                                             // meal-like
                    aIsf[i] = -0.5; aAg[i] = 3.0;
                    regime[i] = Regime.MEAL;
                }
                outlier[i] = rnd.nextDouble() < outlierFraction ? 6.0 : 0.0;
                if (regime[i] == Regime.MEAL) outlier[i] += unloggedMealRise;
            }
        }

        @Override public int anchorCount() { return cleanActual.length; }

        @Override public List<AnchorSample> replay(TwinScales scales) {
            List<AnchorSample> out = new ArrayList<>(cleanActual.length);
            for (int i = 0; i < cleanActual.length; i++) {
                double predicted = cleanActual[i]
                        + aIsf[i] * (scales.isfScale() - trueIsf)
                        + aAg[i]  * (scales.agScale()  - TRUE_AG_SCALE)
                        + noise[i];
                double actual = cleanActual[i] + outlier[i];
                out.add(new AnchorSample(60, predicted, actual, cleanActual[i], regime[i], hour[i]));
            }
            return out;
        }
    }
}
