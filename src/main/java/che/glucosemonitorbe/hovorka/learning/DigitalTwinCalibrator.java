package che.glucosemonitorbe.hovorka.learning;

import java.util.List;

/**
 * Pure calibration algorithm for one user's digital twin. Given a training and a validation
 * {@link PredictionReplayEngine} (time-split so accuracy is measured out-of-sample), it:
 *
 * <ol>
 *   <li>fits {@link TwinScales} on the training anchors by minimising a robust
 *       ({@link RobustLoss#meanHuber Huber}) objective regularised toward physiology
 *       ({@link RobustLoss#ridgeToOne ridge-to-1.0}) with {@link NelderMead};</li>
 *   <li>fits a {@link ResidualBiasModel} on the residuals the calibrated scales leave behind;</li>
 *   <li>scores baseline vs. calibrated MAE on the held-out validation anchors and only reports
 *       {@code improved} when the twin genuinely beats the un-calibrated model out-of-sample.</li>
 * </ol>
 *
 * <p>No Spring/DB dependencies — it operates entirely on engines, which makes the learning logic
 * unit-testable against synthetic data.</p>
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

    public DigitalTwinCalibrator(Config cfg) {
        this.cfg = cfg;
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

        // 1) Fit scales on the training set with a robust, regularised objective.
        NelderMead optimizer = NelderMead.standard();
        NelderMead.Result opt = optimizer.minimize(
                x -> {
                    TwinScales candidate = TwinScales.of(x[0], x[1]);
                    List<AnchorSample> s = train.replay(candidate);
                    return RobustLoss.meanHuber(s) + RobustLoss.ridgeToOne(cfg.ridgeLambda, x[0], x[1]);
                },
                new double[]{1.0, 1.0},
                new double[]{TwinScales.MIN_SCALE, TwinScales.MIN_SCALE},
                new double[]{TwinScales.MAX_SCALE, TwinScales.MAX_SCALE},
                0.15);
        TwinScales scales = TwinScales.of(opt.point()[0], opt.point()[1]).clamped();

        // 2) Fit the residual grid on the calibrated model's leftover error (training set).
        List<AnchorSample> trainCalibrated = train.replay(scales);
        ResidualBiasModel residual = ResidualBiasModel.fit(trainCalibrated);

        // 3) Score out-of-sample: baseline (neutral, no residual) vs. calibrated (scales + residual).
        List<AnchorSample> valBaseline = val.replay(TwinScales.neutral());
        List<AnchorSample> valScaled   = val.replay(scales);
        double maeBaseline   = RobustLoss.mae(valBaseline);
        double maeCalibrated = maeWithResidual(valScaled, residual);
        int valSamples = valBaseline.size();

        // 4) Fit the prediction band's per-horizon σ from the out-of-sample calibrated residuals
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
