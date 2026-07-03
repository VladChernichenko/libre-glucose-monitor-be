package che.glucosemonitorbe.hovorka.learning;

import java.util.List;

/**
 * The uncertainty layer of the digital twin: how far the prediction is typically off as a function
 * of how far ahead it looks. Prediction is probabilistic, so a single line understates what the model
 * actually knows — this model turns the user's own predicted-vs-actual residuals into a per-horizon
 * standard deviation, which the prediction path renders as a confidence band around each point.
 *
 * <h3>What it learns</h3>
 * <p>For each horizon knot (30/60/90/120 min) it estimates the standard deviation of the residual
 * {@code actual − (predicted + hourlyBias)} — i.e. the spread that remains <em>after</em> the mean
 * {@link ResidualBiasModel} correction, so the band is centred on the corrected prediction. The
 * spread naturally grows with horizon: a 30-min forecast is tight, a 4-hour one is wide.</p>
 *
 * <h3>Noise robustness</h3>
 * <ul>
 *   <li><b>Variance shrinkage</b> — each knot's variance is blended toward the pooled variance by
 *       sample count, so a sparse/noisy horizon bucket doesn't produce an over-confident (too narrow)
 *       or freak-wide band.</li>
 *   <li><b>Floor</b> — never below CGM sensor noise ({@value #SD_FLOOR} mmol/L); we never claim
 *       certainty finer than the sensor.</li>
 *   <li><b>Monotone widening</b> — σ is forced non-decreasing across horizons, so the band is an
 *       intuitive widening cone rather than dipping at a lucky horizon.</li>
 *   <li><b>Cap</b> — clamped at {@value #SD_MAX} mmol/L.</li>
 * </ul>
 *
 * <p>Beyond the last trained knot (the live path predicts out to 4–8 h but we only calibrate to
 * 2 h), σ is extrapolated with a diffusion-like √-time growth from the last knot — the principled way
 * to keep widening the band without inventing data.</p>
 */
public final class PredictionUncertaintyModel {

    /** Horizon knots [min] at which σ is estimated (mirrors {@link PredictionReplayEngine.Config#sampleHorizons}). */
    public static final int[] HORIZONS = {30, 60, 90, 120};

    /** Sensor-noise floor on σ [mmol/L] — no band is ever tighter than this. */
    public static final double SD_FLOOR = 0.3;
    /** Safety cap on σ [mmol/L]. */
    public static final double SD_MAX   = 6.0;
    /** Variance-shrinkage pseudo-count: a knot needs ≳{@value} samples before it earns its own σ. */
    private static final double SHRINK_K = 20.0;

    private final double[] sd; // σ per HORIZONS knot [mmol/L], shrunk + floored + monotone

    private PredictionUncertaintyModel(double[] sd) {
        this.sd = sd;
    }

    /**
     * A sensible population prior for users without a personal fit yet — typical CGM prediction-error
     * growth. Used until a twin is calibrated, or when a twin exists but isn't applied.
     */
    public static PredictionUncertaintyModel populationDefault() {
        // ~0.8 / 1.4 / 1.9 / 2.3 mmol/L at 30/60/90/120 min — in line with observed open-loop MAE.
        return new PredictionUncertaintyModel(new double[]{0.8, 1.4, 1.9, 2.3});
    }

    /**
     * Fit per-horizon σ from calibrated anchor samples.
     *
     * @param samples anchor samples evaluated on the <b>calibrated</b> model (ideally out-of-sample)
     * @param bias    the mean-correction model, subtracted so σ is the spread around the corrected point
     */
    public static PredictionUncertaintyModel fit(List<AnchorSample> samples, ResidualBiasModel bias) {
        if (samples == null || samples.isEmpty()) return populationDefault();
        ResidualBiasModel b = bias != null ? bias : ResidualBiasModel.neutral();

        int k = HORIZONS.length;
        int[] n = new int[k];
        double[] sum = new double[k];
        double[] sumSq = new double[k];
        double globalSum = 0.0, globalSumSq = 0.0;
        int globalN = 0;

        for (AnchorSample s : samples) {
            int idx = nearestKnot(s.horizonMin());
            double resid = s.actual() - s.predicted() - b.correctionAt(s.hourOfDay());
            n[idx]++;
            sum[idx] += resid;
            sumSq[idx] += resid * resid;
            globalN++;
            globalSum += resid;
            globalSumSq += resid * resid;
        }

        double pooledVar = variance(globalN, globalSum, globalSumSq);

        double[] sd = new double[k];
        for (int i = 0; i < k; i++) {
            double knotVar = n[i] > 0 ? variance(n[i], sum[i], sumSq[i]) : pooledVar;
            // Empirical-Bayes variance shrinkage toward the pooled estimate.
            double shrunkVar = (n[i] * knotVar + SHRINK_K * pooledVar) / (n[i] + SHRINK_K);
            sd[i] = clampSd(Math.sqrt(Math.max(0.0, shrunkVar)));
        }

        // Monotone widening: σ never decreases as the horizon grows.
        for (int i = 1; i < k; i++) sd[i] = Math.max(sd[i], sd[i - 1]);
        return new PredictionUncertaintyModel(sd);
    }

    /**
     * Predictive standard deviation [mmol/L] at an arbitrary horizon, by interpolating between knots
     * (and √-time extrapolation beyond the last knot).
     */
    public double sdAtHorizon(int horizonMin) {
        int k = HORIZONS.length;
        if (horizonMin <= 0) return SD_FLOOR;

        // Below the first knot: ramp from the sensor floor at t=0 up to σ(firstKnot).
        if (horizonMin <= HORIZONS[0]) {
            double frac = (double) horizonMin / HORIZONS[0];
            return clampSd(SD_FLOOR + frac * (sd[0] - SD_FLOOR));
        }
        // Between knots: linear interpolation.
        for (int i = 1; i < k; i++) {
            if (horizonMin <= HORIZONS[i]) {
                double frac = (double) (horizonMin - HORIZONS[i - 1]) / (HORIZONS[i] - HORIZONS[i - 1]);
                return clampSd(sd[i - 1] + frac * (sd[i] - sd[i - 1]));
            }
        }
        // Beyond the last knot: diffusion-like √-time growth from the last knot.
        int last = HORIZONS[k - 1];
        return clampSd(sd[k - 1] * Math.sqrt((double) horizonMin / last));
    }

    // ── Serialisation (compact CSV for the text column) ─────────────────────────

    /** Serialise the per-knot σ as a comma-separated string. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sd.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Math.round(sd[i] * 1000.0) / 1000.0);
        }
        return sb.toString();
    }

    /** Parse a grid produced by {@link #toCsv()}; returns {@link #populationDefault()} on any problem. */
    public static PredictionUncertaintyModel fromCsv(String csv) {
        if (csv == null || csv.isBlank()) return populationDefault();
        String[] parts = csv.split(",");
        if (parts.length != HORIZONS.length) return populationDefault();
        double[] sd = new double[HORIZONS.length];
        try {
            for (int i = 0; i < sd.length; i++) sd[i] = clampSd(Double.parseDouble(parts[i].trim()));
        } catch (NumberFormatException e) {
            return populationDefault();
        }
        for (int i = 1; i < sd.length; i++) sd[i] = Math.max(sd[i], sd[i - 1]);
        return new PredictionUncertaintyModel(sd);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static int nearestKnot(int horizonMin) {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < HORIZONS.length; i++) {
            int d = Math.abs(HORIZONS[i] - horizonMin);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /** Sample variance from running sums; 0 when fewer than 2 samples. */
    private static double variance(int n, double sum, double sumSq) {
        if (n < 2) return 0.0;
        double mean = sum / n;
        return Math.max(0.0, (sumSq - n * mean * mean) / (n - 1));
    }

    private static double clampSd(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return SD_FLOOR;
        return Math.max(SD_FLOOR, Math.min(SD_MAX, v));
    }
}
