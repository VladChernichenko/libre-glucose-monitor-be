package che.glucosemonitorbe.hovorka.learning;

import java.util.List;

/**
 * Robust loss functions for calibrating against <b>noisy, imperfectly-logged</b> diabetes data.
 *
 * <p>Real logs contain mis-timed injections, forgotten meals, and wrong carb counts. A plain
 * least-squares fit lets a handful of such anchors (which produce huge prediction errors) dominate
 * the objective and drag the fitted parameters to absurd values. The <b>Huber</b> loss is quadratic
 * for small residuals (efficient, like least-squares) but only <b>linear</b> for large residuals, so
 * a mis-logged anchor contributes roughly its magnitude instead of its square - the optimiser fits
 * the bulk of well-behaved data and shrugs off outliers.</p>
 */
public final class RobustLoss {

    private RobustLoss() {}

    /**
     * Default Huber transition point [mmol/L]. Residuals within ±{@value} mmol/L are treated as
     * "normal" (quadratic); larger ones are down-weighted to linear. ~2 mmol/L is about the CGM
     * sensor noise + reasonable model error band, so genuine outliers sit well beyond it.
     */
    public static final double DEFAULT_DELTA = 2.0;

    /**
     * Huber loss for a single residual.
     *
     * @param residual error [mmol/L]
     * @param delta    transition point [mmol/L]
     * @return {@code 0.5*r²} for {@code |r| <= delta}, else {@code delta*(|r| − 0.5*delta)}
     */
    public static double huber(double residual, double delta) {
        double a = Math.abs(residual);
        return a <= delta
                ? 0.5 * residual * residual
                : delta * (a - 0.5 * delta);
    }

    /**
     * Mean Huber loss over a set of samples using {@link #DEFAULT_DELTA}.
     * Returns {@link Double#MAX_VALUE} for an empty set so an unusable parameter vector is never
     * preferred by the optimiser.
     */
    public static double meanHuber(List<AnchorSample> samples) {
        return meanHuber(samples, DEFAULT_DELTA);
    }

    /** Mean Huber loss over a set of samples with an explicit transition point. */
    public static double meanHuber(List<AnchorSample> samples, double delta) {
        if (samples == null || samples.isEmpty()) return Double.MAX_VALUE;
        double sum = 0.0;
        for (AnchorSample s : samples) {
            sum += huber(s.error(), delta);
        }
        return sum / samples.size();
    }

    /**
     * Ridge penalty pulling a set of scales toward their neutral value of 1.0.
     *
     * <p>With sparse or noisy data some dimensions are weakly identifiable; without a prior the
     * optimiser can push them to the clamp bounds on noise alone. This L2 prior keeps a scale at 1.0
     * unless the data pays for moving it - the Bayesian-MAP counterpart to "don't personalise a
     * parameter you can't actually see."</p>
     *
     * @param lambda regularisation strength [mmol/L² per unit²]; 0 disables the prior
     * @param scales the scale values being fitted
     */
    public static double ridgeToOne(double lambda, double... scales) {
        if (lambda <= 0.0) return 0.0;
        double sum = 0.0;
        for (double s : scales) {
            double d = s - 1.0;
            sum += d * d;
        }
        return lambda * sum;
    }

    /** Plain mean absolute error [mmol/L] - the headline accuracy metric (not the fit objective). */
    public static double mae(List<AnchorSample> samples) {
        if (samples == null || samples.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (AnchorSample s : samples) sum += s.absError();
        return sum / samples.size();
    }
}
