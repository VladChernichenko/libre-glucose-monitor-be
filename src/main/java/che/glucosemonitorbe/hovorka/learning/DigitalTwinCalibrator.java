package che.glucosemonitorbe.hovorka.learning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Inverse-dynamics parameter estimation for one user's digital twin. Given a training and a
 * validation {@link AnchorSampleSource} (time-split so accuracy is measured out-of-sample), it fits
 * the personal physiological scales by minimising the squared error between the RK4-integrated
 * Hovorka prediction and the real CGM trace:
 *
 * <pre>{@code   J(θ) = Σ_i ( G_model(t_i; θ) − G_real(t_i) )²  }</pre>
 *
 * <h3>Optimiser — robust Levenberg–Marquardt</h3>
 * <p>The fit uses {@link LmParameterFitter Levenberg–Marquardt} (Apache Commons Math) — the standard
 * choice for nonlinear least-squares over a physiological ODE. Because self-logged data is unreliable
 * (forgotten / mis-timed meals and boluses), a plain squared loss would chase those outliers, so the
 * fit is wrapped in <b>IRLS</b> (iteratively reweighted least squares) with <b>Huber</b> weights: large
 * residuals are down-weighted between LM passes, giving LM the outlier robustness a raw least-squares
 * fit lacks. Regularisation toward physiology is folded in as Tikhonov <b>ridge-to-1.0</b> residual
 * rows (each scale is pulled toward the neutral 1.0), and physiological <b>hard clamps</b> keep every
 * parameter in {@code [MIN_SCALE, MAX_SCALE]}.</p>
 *
 * <h3>Two-stage fit</h3>
 * <ol>
 *   <li><b>BASAL_CHECK</b> — EGP₀ (endogenous glucose production, {@code egpScale}) is identifiable
 *       only away from meals, so it is fitted first on the <b>fasting</b> anchors alone, with insulin
 *       sensitivity and meal magnitude held neutral. Skipped (egpScale = 1.0) when there are too few
 *       fasting anchors to trust.</li>
 *   <li><b>Sensitivity / meal fit</b> — {@code isfScale} and {@code agScale} are then fitted over all
 *       anchors with EGP₀ fixed at the stage-1 value.</li>
 * </ol>
 *
 * <p>Finally a {@link ResidualBiasModel} and {@link PredictionUncertaintyModel} are fitted on what the
 * calibrated physiology leaves behind, and baseline vs. calibrated MAE are scored on the held-out
 * validation window — the twin only reports {@code improved} when it genuinely beats the un-calibrated
 * model out-of-sample.</p>
 *
 * <p>No Spring/DB dependencies — it operates entirely on {@link AnchorSampleSource}s, which makes the
 * learning logic unit-testable against synthetic data.</p>
 */
public final class DigitalTwinCalibrator {

    /** Tunables for the fit. */
    public static final class Config {
        /** Ridge strength pulling scales toward 1.0. Higher = more conservative personalisation. */
        public double ridgeLambda = 0.05;
        /** Minimum training anchors before we attempt a fit at all. */
        public int    minTrainAnchors = 12;
        /** Minimum validation samples before an improvement is trusted. */
        public int    minValSamples = 30;
        /** Fractional out-of-sample MAE improvement required to accept the twin (e.g. 0.02 = 2%). */
        public double minImprovement = 0.02;
        /** Minimum fasting samples before BASAL_CHECK attempts to personalise EGP₀. */
        public int    minFastingSamples = 20;
        /** Huber cut-off (in robust-σ units) beyond which a residual is down-weighted. */
        public double huberDelta = 1.345;
        /** Number of IRLS re-weighting passes. */
        public int    irlsIterations = 4;
    }

    /** Outcome of a calibration run. */
    public record Result(
            TwinScales scales,
            ResidualBiasModel residual,
            PredictionUncertaintyModel uncertainty, // per-horizon σ for the prediction band
            double maeBaseline,     // validation MAE of the un-calibrated model [mmol/L]
            double maeCalibrated,   // validation MAE with scales + residual [mmol/L]
            int trainSamples,
            int valSamples,
            boolean improved,
            String confidence,      // HIGH | MEDIUM | LOW
            String status) {        // human-readable outcome / skip reason

        /** Fractional out-of-sample improvement (0–1); 0 when baseline is unusable. */
        public double improvementFraction() {
            if (Double.isNaN(maeBaseline) || maeBaseline <= 0) return 0.0;
            return Math.max(0.0, (maeBaseline - maeCalibrated) / maeBaseline);
        }
    }

    private final Config cfg;
    private final LmParameterFitter lm;

    public DigitalTwinCalibrator(Config cfg) {
        this.cfg = cfg;
        // Bounded LM: FD Jacobian is fine for O(1) scales; cap work so a full calibration stays fast.
        this.lm = new LmParameterFitter(1e-3, 40, 400);
    }

    public DigitalTwinCalibrator() {
        this(new Config());
    }

    /**
     * Calibrate against a training/validation engine pair.
     *
     * @param train sample source over the earlier part of the window (used to fit)
     * @param val   sample source over the later part of the window (used to score, out-of-sample)
     */
    public Result calibrate(AnchorSampleSource train, AnchorSampleSource val) {
        if (train.anchorCount() < cfg.minTrainAnchors) {
            return notEnough(train.anchorCount());
        }

        // 1) BASAL_CHECK — fit EGP₀ on fasting anchors only (identifiable without meal confounders).
        double egpScale = fitEgpFromFasting(train);

        // 2) Fit ISF + meal-magnitude scales over all anchors, EGP₀ fixed at the BASAL_CHECK value.
        double[] isfAg = robustFit(
                train,
                new double[]{1.0, 1.0},
                new double[]{TwinScales.MIN_SCALE, TwinScales.MIN_SCALE},
                new double[]{TwinScales.MAX_SCALE, TwinScales.MAX_SCALE},
                p -> new TwinScales(p[0], p[1], 1.0, egpScale),
                null);
        TwinScales scales = new TwinScales(isfAg[0], isfAg[1], 1.0, egpScale).clamped();

        // 3) Fit the residual grid on the calibrated model's leftover error (training set).
        List<AnchorSample> trainCalibrated = train.replay(scales);
        ResidualBiasModel residual = ResidualBiasModel.fit(trainCalibrated);

        // 4) Score out-of-sample: baseline (neutral, no residual) vs. calibrated (scales + residual).
        List<AnchorSample> valBaseline = val.replay(TwinScales.neutral());
        List<AnchorSample> valScaled   = val.replay(scales);
        double maeBaseline   = RobustLoss.mae(valBaseline);
        double maeCalibrated = maeWithResidual(valScaled, residual);
        int valSamples = valBaseline.size();

        // 5) Fit the prediction band's per-horizon σ from the out-of-sample calibrated residuals
        //    (honest predictive spread; falls back to the population prior when val is thin).
        PredictionUncertaintyModel uncertainty = valScaled.isEmpty()
                ? PredictionUncertaintyModel.fit(train.replay(scales), residual)
                : PredictionUncertaintyModel.fit(valScaled, residual);

        double improvement = (maeBaseline > 0)
                ? (maeBaseline - maeCalibrated) / maeBaseline : 0.0;
        boolean improved = valSamples >= cfg.minValSamples
                && improvement >= cfg.minImprovement
                && maeCalibrated < maeBaseline;

        String confidence = confidence(valSamples, improvement);
        String status = improved
                ? String.format("calibrated: val MAE %.2f→%.2f mmol/L (%.0f%% better)",
                        maeBaseline, maeCalibrated, improvement * 100.0)
                : String.format("no improvement out-of-sample (val MAE %.2f→%.2f, n=%d)",
                        maeBaseline, maeCalibrated, valSamples);

        return new Result(scales, residual, uncertainty, maeBaseline, maeCalibrated,
                trainCalibrated.size(), valSamples, improved, confidence, status);
    }

    // ── BASAL_CHECK ──────────────────────────────────────────────────────────────

    /**
     * Fit {@code egpScale} on the fasting anchors alone. Returns 1.0 (neutral) when the window lacks
     * enough fasting signal to personalise endogenous glucose production reliably.
     */
    private double fitEgpFromFasting(AnchorSampleSource train) {
        long fastingCount = train.replay(TwinScales.neutral()).stream()
                .filter(s -> s.regime() == Regime.FASTING).count();
        if (fastingCount < cfg.minFastingSamples) {
            return 1.0;
        }
        double[] egp = robustFit(
                train,
                new double[]{1.0},
                new double[]{TwinScales.MIN_SCALE},
                new double[]{TwinScales.MAX_SCALE},
                p -> new TwinScales(1.0, 1.0, 1.0, p[0]),
                s -> s.regime() == Regime.FASTING);
        return TwinScales.clamp(egp[0]);
    }

    // ── Robust Levenberg–Marquardt (IRLS + Huber) ────────────────────────────────

    /**
     * Robustly fit the parameter vector that {@code toScales} maps into a {@link TwinScales}, over the
     * samples selected by {@code filter} (all samples when {@code filter} is null). Runs LM inside an
     * IRLS loop that re-derives Huber weights from the current residuals each pass, and appends
     * ridge-to-1.0 rows so the fit is regularised toward physiology and never rank-deficient.
     */
    private double[] robustFit(AnchorSampleSource src, double[] start, double[] lower, double[] upper,
                               Function<double[], TwinScales> toScales, Predicate<AnchorSample> filter) {
        double[] p = LmParameterFitter.clampToBounds(start.clone(), lower, upper);

        for (int iter = 0; iter < cfg.irlsIterations; iter++) {
            List<AnchorSample> sel = select(src.replay(toScales.apply(p)), filter);
            if (sel.isEmpty()) return p;

            final double[] weights = huberWeights(sel);
            final int m = sel.size();
            final double ridge = Math.sqrt(cfg.ridgeLambda);

            LmParameterFitter.ResidualModel model = params -> {
                List<AnchorSample> s = select(src.replay(toScales.apply(params)), filter);
                int rows = Math.min(s.size(), m);
                double[] r = new double[rows + params.length];
                for (int i = 0; i < rows; i++) {
                    r[i] = Math.sqrt(weights[i]) * s.get(i).error();
                }
                for (int j = 0; j < params.length; j++) {
                    r[rows + j] = ridge * (params[j] - 1.0);   // Tikhonov pull toward physiology
                }
                return r;
            };

            LmParameterFitter.Result res = lm.fit(model, p, lower, upper);
            double delta = maxAbsDiff(p, res.params());
            p = res.params();
            if (delta < 1e-3) break;
        }
        return p;
    }

    /** Huber weights from the samples' residuals, scaled by a robust (MAD) spread estimate. */
    private double[] huberWeights(List<AnchorSample> samples) {
        double[] resid = new double[samples.size()];
        for (int i = 0; i < resid.length; i++) resid[i] = samples.get(i).error();
        double sigma = robustScale(resid);
        double[] w = new double[resid.length];
        for (int i = 0; i < resid.length; i++) {
            double z = Math.abs(resid[i]) / sigma;
            w[i] = z <= cfg.huberDelta ? 1.0 : cfg.huberDelta / z;
        }
        return w;
    }

    /** Robust spread estimate: 1.4826·MAD, floored so it never collapses to zero. */
    private static double robustScale(double[] values) {
        if (values.length == 0) return 1.0;
        double median = median(values.clone());
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) dev[i] = Math.abs(values[i] - median);
        double mad = median(dev);
        return Math.max(0.3, 1.4826 * mad);   // 0.3 mmol/L floor ~ CGM sensor noise
    }

    private static double median(double[] a) {
        Arrays.sort(a);
        int n = a.length;
        if (n == 0) return 0.0;
        return (n % 2 == 1) ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }

    private static List<AnchorSample> select(List<AnchorSample> all, Predicate<AnchorSample> filter) {
        if (filter == null) return all;
        List<AnchorSample> out = new ArrayList<>();
        for (AnchorSample s : all) if (filter.test(s)) out.add(s);
        return out;
    }

    private static double maxAbsDiff(double[] a, double[] b) {
        double max = 0.0;
        for (int i = 0; i < a.length; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
    }

    // ── Scoring helpers ──────────────────────────────────────────────────────────

    /** MAE with the residual correction applied to each predicted point. */
    private static double maeWithResidual(List<AnchorSample> samples, ResidualBiasModel residual) {
        if (samples.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (AnchorSample s : samples) {
            double corrected = s.predicted() + residual.correctionAt(s.hourOfDay());
            sum += Math.abs(corrected - s.actual());
        }
        return sum / samples.size();
    }

    private static String confidence(int valSamples, double improvement) {
        if (valSamples >= 100 && improvement >= 0.10) return "HIGH";
        if (valSamples >= 40 && improvement >= 0.03) return "MEDIUM";
        return "LOW";
    }

    private Result notEnough(int anchors) {
        return new Result(TwinScales.neutral(), ResidualBiasModel.neutral(),
                PredictionUncertaintyModel.populationDefault(),
                Double.NaN, Double.NaN, 0, 0, false, "LOW",
                "insufficient data: " + anchors + " training anchors (< " + cfg.minTrainAnchors + ")");
    }
}
