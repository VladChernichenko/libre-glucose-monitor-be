package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.hovorka.ActivityGains;

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
 * <h3>Optimiser - robust Levenberg-Marquardt</h3>
 * <p>The fit uses {@link LmParameterFitter Levenberg-Marquardt} (Apache Commons Math) - the standard
 * choice for nonlinear least-squares over a physiological ODE. Because self-logged data is unreliable
 * (forgotten / mis-timed meals and boluses), a plain squared loss would chase those outliers, so the
 * fit is wrapped in <b>IRLS</b> (iteratively reweighted least squares) with <b>Huber</b> weights: large
 * residuals are down-weighted between LM passes, giving LM the outlier robustness a raw least-squares
 * fit lacks. Regularisation toward physiology is a Gaussian <b>prior centred on 1.0</b> for each scale,
 * folded in as one extra residual row per parameter (see {@link Config#priorSd}), and physiological
 * <b>hard clamps</b> keep every parameter in {@code [MIN_SCALE, MAX_SCALE]}.</p>
 *
 * <h3>Three-stage fit</h3>
 * <ol>
 *   <li><b>BASAL_CHECK</b> - EGP₀ (endogenous glucose production, {@code egpScale}) is identifiable
 *       only away from meals, so it is fitted first on the <b>fasting</b> anchors alone, with insulin
 *       sensitivity and meal magnitude held neutral. Skipped (egpScale = 1.0) when there are too few
 *       fasting anchors to trust.</li>
 *   <li><b>Sensitivity fit</b> - {@code isfScale} is fitted on the trusted <b>correction</b> anchors
 *       alone ({@link Regime#CORRECTION}), with EGP₀ fixed. Skipped (isfScale = 1.0) when there are
 *       too few. Fitted over all anchors, every bolus whose carbs went unlogged read as insulin that
 *       did nothing and drove the scale to its lower bound.</li>
 *   <li><b>Meal fit</b> - {@code agScale} is then fitted over all anchors with the other two fixed.</li>
 * </ol>
 *
 * <p>Finally a {@link PredictionUncertaintyModel} is fitted on what the calibrated physiology leaves
 * behind (no hourly {@link ResidualBiasModel}: it turned dinner-time misses into a clock-based rise
 * added to every evening forecast, meal or not), and baseline vs. calibrated MAE are scored on the held-out
 * validation window - the twin only reports {@code improved} when it genuinely beats the un-calibrated
 * model out-of-sample, and never when a fitted scale rests on a clamp bound.</p>
 *
 * <p>No Spring/DB dependencies - it operates entirely on {@link AnchorSampleSource}s, which makes the
 * learning logic unit-testable against synthetic data.</p>
 */
public final class DigitalTwinCalibrator {

    /** Tunables for the fit. */
    public static final class Config {
        /**
         * Standard deviation of the prior on each scale, centred on 1.0. Smaller = more conservative
         * personalisation.
         *
         * <p>The prior enters the least-squares problem as the row {@code (σ_r / priorSd) × (scale − 1)},
         * where σ_r is the robust spread of the residuals. That is the MAP form of a Gaussian prior
         * against Gaussian errors of spread σ_r, so its weight does not depend on the unit of the
         * residuals and does not vanish as samples accumulate the way a fixed constant does. 0.3
         * says a scale outside roughly 0.4-1.6 would be surprising, which is the clamp band.</p>
         */
        public double priorSd = 0.3;
        /**
         * Samples that share an anchor are one trajectory read at several horizons, not independent
         * observations. The prior row is multiplied by the square root of this so a sample counts
         * for one anchor's worth of evidence. Matches the default number of sampled horizons.
         */
        public int    samplesPerAnchor = PredictionUncertaintyModel.HORIZONS.length;
        /** Minimum training anchors before we attempt a fit at all. */
        public int    minTrainAnchors = 12;
        /** Minimum validation samples before an improvement is trusted. */
        public int    minValSamples = 30;
        /** Fractional out-of-sample MAE improvement required to accept the twin (e.g. 0.02 = 2%). */
        public double minImprovement = 0.02;
        /** Minimum fasting samples before BASAL_CHECK attempts to personalise EGP₀. */
        public int    minFastingSamples = 20;
        /** Minimum trusted-correction samples before the ISF scale is personalised. */
        public int    minCorrectionSamples = 20;
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
            double maeCalibrated,   // validation MAE with the fitted scales [mmol/L]
            int trainSamples,
            int valSamples,
            boolean improved,
            String confidence,      // HIGH | MEDIUM | LOW
            String status) {        // human-readable outcome / skip reason

        /** Fractional out-of-sample improvement (0-1); 0 when baseline is unusable. */
        public double improvementFraction() {
            if (Double.isNaN(maeBaseline) || maeBaseline <= 0) return 0.0;
            return Math.max(0.0, (maeBaseline - maeCalibrated) / maeBaseline);
        }
    }

    private final Config cfg;
    private final LmParameterFitter lm;

    public DigitalTwinCalibrator(Config cfg) {
        this.cfg = cfg;
        // Bounded LM: FD Jacobian is fine for O(1) scales. Budget is generous enough to converge on
        // most users; LmParameterFitter degrades gracefully to the start point if a fit still can't.
        this.lm = new LmParameterFitter(1e-3, 100, 2000);
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

        // 1) BASAL_CHECK - fit EGP₀ on fasting anchors only (identifiable without meal confounders).
        double egpScale = fitEgpFromFasting(train);

        // 2) Fit ISF on trusted corrections only. A bolus whose carbs were never logged looks like
        //    insulin that does nothing, and over all anchors that pulled ISF to its lower bound.
        double isfScale = fitIsfFromCorrections(train, egpScale);

        // 3) Fit the meal-magnitude scale over all anchors, ISF and EGP₀ fixed.
        double[] ag = robustFit(
                train,
                new double[]{1.0},
                new double[]{TwinScales.MIN_SCALE},
                new double[]{TwinScales.MAX_SCALE},
                p -> new TwinScales(isfScale, p[0], 1.0, egpScale),
                null);
        TwinScales scales = new TwinScales(isfScale, ag[0], 1.0, egpScale).clamped();

        // 4) No hourly residual grid: a per-hour offset learned from meal misses was added to every
        //    forecast at that clock hour, bending quiet forecasts (2026-11-27). Kept neutral.
        List<AnchorSample> trainCalibrated = train.replay(scales);
        ResidualBiasModel residual = ResidualBiasModel.neutral();

        // 5) Score out-of-sample: baseline (neutral scales) vs. calibrated scales.
        List<AnchorSample> valBaseline = val.replay(TwinScales.neutral());
        List<AnchorSample> valScaled   = val.replay(scales);
        double maeBaseline   = RobustLoss.mae(valBaseline);
        double maeCalibrated = RobustLoss.mae(valScaled);
        int valSamples = valBaseline.size();

        // 6) Fit the prediction band's per-horizon σ from the out-of-sample calibrated residuals
        //    (honest predictive spread; falls back to the population prior when val is thin).
        PredictionUncertaintyModel uncertainty = valScaled.isEmpty()
                ? PredictionUncertaintyModel.fit(train.replay(scales), residual)
                : PredictionUncertaintyModel.fit(valScaled, residual);

        double improvement = (maeBaseline > 0)
                ? (maeBaseline - maeCalibrated) / maeBaseline : 0.0;
        // A scale resting on its clamp did not converge: the fit wanted to go further and is using
        // that parameter to absorb an error it cannot express. Never apply such a twin.
        String pinned = pinnedScale(scales);
        boolean improved = pinned == null
                && valSamples >= cfg.minValSamples
                && improvement >= cfg.minImprovement
                && maeCalibrated < maeBaseline;

        String confidence = confidence(valSamples, improvement);
        String status = improved
                ? String.format("calibrated: val MAE %.2f->%.2f mmol/L (%.0f%% better)",
                        maeBaseline, maeCalibrated, improvement * 100.0)
                : pinned != null
                ? String.format("rejected: %s pinned at bound (val MAE %.2f->%.2f, n=%d)",
                        pinned, maeBaseline, maeCalibrated, valSamples)
                : String.format("no improvement out-of-sample (val MAE %.2f->%.2f, n=%d)",
                        maeBaseline, maeCalibrated, valSamples);

        return new Result(scales, residual, uncertainty, maeBaseline, maeCalibrated,
                trainCalibrated.size(), valSamples, improved, confidence, status);
    }

    // -- Activity stage -----------------------------------------------------------

    /** Minimum logged activities before the activity gains are personalised at all. */
    public static final int MIN_ACTIVITIES = 5;

    /** Outcome of the activity calibration stage. */
    public record ActivityFit(
            ActivityGains gains,
            boolean applied,        // gate: fitted gains beat NEUTRAL out-of-sample on enough data
            double maeWithout,      // validation MAE with NEUTRAL gains [mmol/L]
            double maeWith,         // validation MAE with the fitted gains [mmol/L]
            int activityCount,
            String status) {}

    /**
     * Fit the personal activity gains on top of an already-fitted twin. The vector
     * {@code [gainScale, halfLife/120]} is fitted with the same robust LM (IRLS + Huber + ridge to
     * 1.0) while {@code fixed} is held constant, then scored on {@code val} against NEUTRAL gains
     * (no residual model on either side). The fit is only {@code applied} when it beats NEUTRAL by at
     * least {@code minImprovement} (fractional MAE) on at least {@code minValSamples} validation
     * samples.
     */
    public ActivityFit fitActivity(AnchorSampleSource train, AnchorSampleSource val,
                                   TwinScales fixed, int activityCount) {
        if (activityCount < MIN_ACTIVITIES) {
            return new ActivityFit(ActivityGains.NEUTRAL, false, Double.NaN, Double.NaN,
                    activityCount, "fewer than " + MIN_ACTIVITIES + " activities");
        }
        List<AnchorSample> valWithout = val.replay(fixed, ActivityGains.NEUTRAL);
        double maeWithout = RobustLoss.mae(valWithout);
        if (valWithout.size() < cfg.minValSamples) {
            return new ActivityFit(ActivityGains.NEUTRAL, false, maeWithout, Double.NaN,
                    activityCount, "too little validation data");
        }

        double halfLifeUnit = ActivityGains.NEUTRAL.tailHalfLifeMin();
        double[] p = robustFit(
                q -> train.replay(fixed, toGains(q, halfLifeUnit)),
                new double[]{1.0, 1.0},
                new double[]{ActivityGains.MIN_GAIN_SCALE, ActivityGains.MIN_GAIN_SCALE},
                new double[]{ActivityGains.MAX_GAIN_SCALE, ActivityGains.MAX_GAIN_SCALE},
                null);
        ActivityGains fitted = toGains(p, halfLifeUnit);

        double maeWith = RobustLoss.mae(val.replay(fixed, fitted));
        // Same standard as the twin itself: a fractional out-of-sample gain of at least minImprovement,
        // so a fit that only learned noise (a hair better by chance) is not applied.
        boolean applied = maeWithout > 0
                && (maeWithout - maeWith) / maeWithout >= cfg.minImprovement;
        String status = applied
                ? String.format("activity applied: val MAE %.2f->%.2f mmol/L", maeWithout, maeWith)
                : String.format("activity not applied: val MAE %.2f->%.2f mmol/L", maeWithout, maeWith);
        return new ActivityFit(fitted, applied, maeWithout, maeWith, activityCount, status);
    }

    // -- Insulin curve stage ------------------------------------------------------

    /** Minimum logged corrections of an insulin before its curve is personalised at all. */
    public static final int MIN_CURVE_DOSES = 8;

    /** Outcome of the insulin-curve stage. */
    public record CurveFit(
            CurveScale scale,
            boolean applied,        // gate: beat the catalog curve out-of-sample, enough data, not pinned
            double maeWithout,      // validation MAE on corrections with the catalog curve [mmol/L]
            double maeWith,         // validation MAE on corrections with the fitted curve [mmol/L]
            int doseCount,
            String status) {}

    /**
     * Fit the personal action curve of {@code insulinCode} on top of an already-fitted twin: the
     * vector {@code [peakScale, diaScale]}, with the same robust LM and prior at 1.0, on the trusted
     * {@link Regime#CORRECTION} samples only - the one regime where a curve's shape is not confounded
     * by meal absorption. Scored on the validation corrections against the catalog curve, and only
     * {@code applied} when it beats it by {@code minImprovement}, on at least
     * {@code minCorrectionSamples} validation samples, with neither multiplier resting on a bound.
     *
     * <p>The sources should replay a horizon long enough to see the curve's tail (about the
     * insulin's duration); at a 2-hour horizon the duration is not identifiable.</p>
     */
    public CurveFit fitInsulinCurve(AnchorSampleSource train, AnchorSampleSource val,
                                    TwinScales fixed, String insulinCode, int doseCount) {
        if (doseCount < MIN_CURVE_DOSES) {
            return new CurveFit(CurveScale.NEUTRAL, false, Double.NaN, Double.NaN, doseCount,
                    "fewer than " + MIN_CURVE_DOSES + " corrections of " + insulinCode);
        }
        Predicate<AnchorSample> corrections = s -> s.regime() == Regime.CORRECTION;
        List<AnchorSample> valWithout = select(
                val.replay(fixed, ActivityGains.NEUTRAL, insulinCode, CurveScale.NEUTRAL), corrections);
        double maeWithout = RobustLoss.mae(valWithout);
        if (valWithout.size() < cfg.minCorrectionSamples) {
            return new CurveFit(CurveScale.NEUTRAL, false, maeWithout, Double.NaN, doseCount,
                    "too few correction samples to validate on");
        }

        double[] p = robustFit(
                q -> train.replay(fixed, ActivityGains.NEUTRAL, insulinCode, new CurveScale(q[0], q[1])),
                new double[]{1.0, 1.0},
                new double[]{CurveScale.MIN_PEAK_SCALE, CurveScale.MIN_DIA_SCALE},
                new double[]{CurveScale.MAX_PEAK_SCALE, CurveScale.MAX_DIA_SCALE},
                corrections);
        CurveScale fitted = new CurveScale(p[0], p[1]).clamped();

        double maeWith = RobustLoss.mae(select(
                val.replay(fixed, ActivityGains.NEUTRAL, insulinCode, fitted), corrections));
        boolean pinned = onBound(fitted.peakScale(), CurveScale.MIN_PEAK_SCALE, CurveScale.MAX_PEAK_SCALE)
                || onBound(fitted.diaScale(), CurveScale.MIN_DIA_SCALE, CurveScale.MAX_DIA_SCALE);
        boolean applied = !pinned && maeWithout > 0
                && (maeWithout - maeWith) / maeWithout >= cfg.minImprovement;
        String status = applied
                ? String.format("curve applied: val MAE %.2f->%.2f mmol/L", maeWithout, maeWith)
                : pinned
                ? String.format("curve rejected: pinned at bound (val MAE %.2f->%.2f)", maeWithout, maeWith)
                : String.format("curve not applied: val MAE %.2f->%.2f mmol/L", maeWithout, maeWith);
        return new CurveFit(fitted, applied, maeWithout, maeWith, doseCount, status);
    }

    private static boolean onBound(double v, double lo, double hi) {
        return v <= lo + PINNED_TOLERANCE || v >= hi - PINNED_TOLERANCE;
    }

    private static ActivityGains toGains(double[] p, double halfLifeUnit) {
        return new ActivityGains(p[0], p[1] * halfLifeUnit).clamped();
    }

    // -- BASAL_CHECK --------------------------------------------------------------

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

    // -- ISF from corrections -----------------------------------------------------

    /**
     * Fit {@code isfScale} on the {@link Regime#CORRECTION} anchors alone, with EGP₀ fixed. Returns
     * 1.0 (neutral) when the window holds too few trusted corrections to personalise sensitivity.
     */
    private double fitIsfFromCorrections(AnchorSampleSource train, double egpScale) {
        TwinScales start = new TwinScales(1.0, 1.0, 1.0, egpScale);
        long corrections = train.replay(start).stream()
                .filter(s -> s.regime() == Regime.CORRECTION).count();
        if (corrections < cfg.minCorrectionSamples) {
            return 1.0;
        }
        double[] isf = robustFit(
                train,
                new double[]{1.0},
                new double[]{TwinScales.MIN_SCALE},
                new double[]{TwinScales.MAX_SCALE},
                p -> new TwinScales(p[0], 1.0, 1.0, egpScale),
                s -> s.regime() == Regime.CORRECTION);
        return TwinScales.clamp(isf[0]);
    }

    /** How close to a clamp a fitted scale may sit before it counts as pinned. */
    static final double PINNED_TOLERANCE = 1e-3;

    /** Name of the first scale resting on a clamp bound, or null when none is. */
    static String pinnedScale(TwinScales s) {
        if (isPinned(s.isfScale())) return "isfScale";
        if (isPinned(s.agScale()))  return "agScale";
        if (isPinned(s.egpScale())) return "egpScale";
        return null;
    }

    private static boolean isPinned(double scale) {
        return scale <= TwinScales.MIN_SCALE + PINNED_TOLERANCE
                || scale >= TwinScales.MAX_SCALE - PINNED_TOLERANCE;
    }

    // -- Robust Levenberg-Marquardt (IRLS + Huber) --------------------------------

    /**
     * Robustly fit the parameter vector that {@code toScales} maps into a {@link TwinScales}, over the
     * samples selected by {@code filter} (all samples when {@code filter} is null). Runs LM inside an
     * IRLS loop that re-derives Huber weights from the current residuals each pass, and appends
     * ridge-to-1.0 rows so the fit is regularised toward physiology and never rank-deficient.
     */
    private double[] robustFit(AnchorSampleSource src, double[] start, double[] lower, double[] upper,
                               Function<double[], TwinScales> toScales, Predicate<AnchorSample> filter) {
        return robustFit(p -> src.replay(toScales.apply(p)), start, lower, upper, filter);
    }

    /** As above, with an arbitrary parameter-vector-to-samples replay (e.g. scales held fixed). */
    private double[] robustFit(Function<double[], List<AnchorSample>> replay, double[] start,
                               double[] lower, double[] upper, Predicate<AnchorSample> filter) {
        double[] p = LmParameterFitter.clampToBounds(start.clone(), lower, upper);

        for (int iter = 0; iter < cfg.irlsIterations; iter++) {
            List<AnchorSample> sel = select(replay.apply(p), filter);
            if (sel.isEmpty()) return p;

            final double sigma = robustScale(errors(sel));
            final double[] weights = huberWeights(sel, sigma);
            final int m = sel.size();
            final double ridge = priorRowWeight(sigma);

            LmParameterFitter.ResidualModel model = params -> {
                List<AnchorSample> s = select(replay.apply(params), filter);
                int rows = Math.min(s.size(), m);
                double[] r = new double[rows + params.length];
                for (int i = 0; i < rows; i++) {
                    r[i] = Math.sqrt(weights[i]) * s.get(i).error();
                }
                for (int j = 0; j < params.length; j++) {
                    r[rows + j] = ridge * (params[j] - 1.0);   // prior pull toward physiology
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

    /** Weight of the prior row for a residual spread {@code sigma} - see {@link Config#priorSd}. */
    double priorRowWeight(double sigma) {
        if (cfg.priorSd <= 0) return 0.0;   // prior switched off
        return sigma / cfg.priorSd * Math.sqrt(Math.max(1, cfg.samplesPerAnchor));
    }

    private static double[] errors(List<AnchorSample> samples) {
        double[] resid = new double[samples.size()];
        for (int i = 0; i < resid.length; i++) resid[i] = samples.get(i).error();
        return resid;
    }

    /** Huber weights from the samples' residuals, scaled by a robust (MAD) spread {@code sigma}. */
    private double[] huberWeights(List<AnchorSample> samples, double sigma) {
        double[] resid = errors(samples);
        double[] w = new double[resid.length];
        for (int i = 0; i < resid.length; i++) {
            double z = Math.abs(resid[i]) / sigma;
            w[i] = z <= cfg.huberDelta ? 1.0 : cfg.huberDelta / z;
        }
        return w;
    }

    /** Robust spread estimate: 1.4826*MAD, floored so it never collapses to zero. */
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

    // -- Scoring helpers ----------------------------------------------------------

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
