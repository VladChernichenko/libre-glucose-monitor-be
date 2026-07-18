package che.glucosemonitorbe.hovorka.learning;

import java.util.List;

/**
 * The data-driven layer of the digital twin: a time-of-day grid of additive corrections learned
 * from the residuals the physiological model leaves behind.
 *
 * <p>After the {@link che.glucosemonitorbe.hovorka.HovorkaParameters} are calibrated, systematic
 * misses remain that the ODE cannot express from the logged inputs - dawn phenomenon, an
 * afternoon activity dip, chronically unlogged snacks at a particular hour. Averaged over many days
 * these show up as a stable bias at a given clock hour. This model captures {@code actual − predicted}
 * per hour-of-day and adds it back to future predictions.</p>
 *
 * <h3>Noise robustness - empirical-Bayes shrinkage</h3>
 * <p>A raw per-hour mean over a sparse, noisy window would itself overfit. Each hour's correction is
 * therefore shrunk toward the user's global mean residual (and the global mean toward zero) with a
 * pseudo-count {@code k}: an hour with few samples barely moves off the pooled estimate, while an
 * hour with many consistent samples earns its own value. This is what lets the layer "find the
 * pattern" without chasing logging noise.</p>
 */
public final class ResidualBiasModel {

    /** One correction per clock hour. */
    public static final int BUCKETS = 24;
    /** Shrinkage pseudo-count: an hour needs ≳{@value} samples before it overrides the pooled mean. */
    private static final double SHRINK_K = 12.0;
    /** Pseudo-count pulling the global mean residual toward zero. */
    private static final double GLOBAL_SHRINK_K = 30.0;
    /** Hard clamp on any single correction [mmol/L] - a safety rail against runaway bias. */
    public static final double MAX_CORRECTION = 2.5;

    private final double[] bias; // length BUCKETS, in mmol/L, already shrunk + clamped

    private ResidualBiasModel(double[] bias) {
        this.bias = bias;
    }

    /** The neutral model: zero correction at every hour. */
    public static ResidualBiasModel neutral() {
        return new ResidualBiasModel(new double[BUCKETS]);
    }

    /**
     * Fit the per-hour correction grid from residual samples.
     *
     * @param samples anchor samples evaluated on the <b>calibrated</b> physiological model
     */
    public static ResidualBiasModel fit(List<AnchorSample> samples) {
        if (samples == null || samples.isEmpty()) return neutral();

        double[] sum = new double[BUCKETS];
        int[] count = new int[BUCKETS];
        double globalSum = 0.0;
        int globalCount = 0;

        for (AnchorSample s : samples) {
            int h = ((s.hourOfDay() % BUCKETS) + BUCKETS) % BUCKETS;
            double residual = s.actual() - s.predicted(); // correction needed to reach reality
            sum[h] += residual;
            count[h]++;
            globalSum += residual;
            globalCount++;
        }

        // Global mean residual, itself shrunk toward 0 so a short window doesn't bake in a bias.
        double globalMean = globalCount > 0
                ? globalSum / (globalCount + GLOBAL_SHRINK_K)
                : 0.0;

        double[] bias = new double[BUCKETS];
        for (int h = 0; h < BUCKETS; h++) {
            double shrunk;
            if (count[h] == 0) {
                shrunk = globalMean;
            } else {
                double rawMean = sum[h] / count[h];
                // Empirical-Bayes: blend the hour's own mean with the global mean by sample count.
                shrunk = (count[h] * rawMean + SHRINK_K * globalMean) / (count[h] + SHRINK_K);
            }
            bias[h] = clampCorrection(shrunk);
        }
        return new ResidualBiasModel(bias);
    }

    /** Additive correction [mmol/L] to apply to a prediction whose point falls at {@code hourOfDay}. */
    public double correctionAt(int hourOfDay) {
        int h = ((hourOfDay % BUCKETS) + BUCKETS) % BUCKETS;
        return bias[h];
    }

    /**
     * Additive correction [mmol/L] interpolated across the hour so the residual varies smoothly
     * instead of stepping at each clock-hour boundary. The bias for bucket {@code h} is treated as
     * the value at the bucket's centre (:30); points before the centre blend with the previous
     * bucket and points after it blend with the next. This eliminates the vertical step in the
     * prediction curve that a piecewise-constant {@link #correctionAt(int)} produced at every :00.
     *
     * @param hourOfDay   0..23 hour component of the prediction point
     * @param minuteOfHour 0..59 minute component of the prediction point
     */
    public double correctionAt(int hourOfDay, int minuteOfHour) {
        int h = ((hourOfDay % BUCKETS) + BUCKETS) % BUCKETS;
        // Position within the hour relative to the bucket centre (:30), in [-0.5, 0.5).
        double offsetFromCentre = (minuteOfHour - 30.0) / 60.0;
        int neighbour;
        double w; // weight applied to the neighbour bucket
        if (offsetFromCentre >= 0.0) {
            neighbour = (h + 1) % BUCKETS;
            w = offsetFromCentre;             // 0 at :30 -> 0.5 approaching :90 (next centre)
        } else {
            neighbour = ((h - 1) % BUCKETS + BUCKETS) % BUCKETS;
            w = -offsetFromCentre;            // 0 at :30 -> 0.5 approaching :-30 (prev centre)
        }
        return bias[h] * (1.0 - w) + bias[neighbour] * w;
    }

    /** True if every correction is effectively zero (nothing learned). */
    public boolean isNeutral() {
        for (double b : bias) if (Math.abs(b) > 1e-6) return false;
        return true;
    }

    private static double clampCorrection(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.max(-MAX_CORRECTION, Math.min(MAX_CORRECTION, v));
    }

    // -- Serialisation (compact CSV for the JSON/text column) --------------------

    /** Serialise the 24 corrections as a comma-separated string. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < BUCKETS; i++) {
            if (i > 0) sb.append(',');
            sb.append(Math.round(bias[i] * 1000.0) / 1000.0);
        }
        return sb.toString();
    }

    /** Parse a grid previously produced by {@link #toCsv()}; returns {@link #neutral()} on any problem. */
    public static ResidualBiasModel fromCsv(String csv) {
        if (csv == null || csv.isBlank()) return neutral();
        String[] parts = csv.split(",");
        if (parts.length != BUCKETS) return neutral();
        double[] bias = new double[BUCKETS];
        try {
            for (int i = 0; i < BUCKETS; i++) bias[i] = clampCorrection(Double.parseDouble(parts[i].trim()));
        } catch (NumberFormatException e) {
            return neutral();
        }
        return new ResidualBiasModel(bias);
    }
}
