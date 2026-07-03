package che.glucosemonitorbe.hovorka.learning;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;

/**
 * Compact, dependency-free Nelder–Mead (downhill simplex) optimiser for low-dimensional,
 * derivative-free objectives.
 *
 * <p>Chosen for parameter calibration because the objective — mean prediction error of a
 * forward ODE integration — is not analytically differentiable and has mild parameter coupling
 * (e.g. insulin sensitivity vs. meal magnitude both shift the post-meal curve). Nelder–Mead handles
 * coupling without gradients and converges reliably for the handful of scales we fit.</p>
 *
 * <p>Box constraints are enforced by clamping every evaluated vertex into {@code [lower, upper]}
 * before the objective is called, which keeps the search inside the physiological band.</p>
 */
public final class NelderMead {

    private final int maxIterations;
    private final double tolerance;

    public NelderMead(int maxIterations, double tolerance) {
        this.maxIterations = maxIterations;
        this.tolerance = tolerance;
    }

    /** Sensible defaults for calibrating 2–5 parameters. */
    public static NelderMead standard() {
        return new NelderMead(300, 1e-4);
    }

    /** Result of a minimisation: the best point found and its objective value. */
    public record Result(double[] point, double value, int iterations) {}

    /**
     * Minimise {@code objective} starting from {@code start}, with each coordinate clamped to
     * {@code [lower[i], upper[i]]}.
     *
     * @param objective function to minimise (must handle any point inside the box)
     * @param start     initial guess (typically all-ones for scale parameters)
     * @param lower     per-dimension lower bounds
     * @param upper     per-dimension upper bounds
     * @param initialStep initial simplex edge length in each dimension
     */
    public Result minimize(Function<double[], Double> objective,
                           double[] start, double[] lower, double[] upper, double initialStep) {
        int n = start.length;
        // Standard Nelder–Mead coefficients.
        final double alpha = 1.0;   // reflection
        final double gamma = 2.0;   // expansion
        final double rho   = 0.5;   // contraction
        final double sigma = 0.5;   // shrink

        // Build the initial simplex: start point plus one offset vertex per dimension.
        double[][] simplex = new double[n + 1][n];
        double[] values = new double[n + 1];
        simplex[0] = clamp(start.clone(), lower, upper);
        for (int i = 0; i < n; i++) {
            double[] v = start.clone();
            v[i] += initialStep;
            simplex[i + 1] = clamp(v, lower, upper);
        }
        for (int i = 0; i <= n; i++) values[i] = objective.apply(simplex[i]);

        int iter = 0;
        for (; iter < maxIterations; iter++) {
            // Order vertices best→worst.
            Integer[] order = new Integer[n + 1];
            for (int i = 0; i <= n; i++) order[i] = i;
            final double[] valSnapshot = values;
            Arrays.sort(order, Comparator.comparingDouble(i -> valSnapshot[i]));
            double[][] sortedS = new double[n + 1][];
            double[] sortedV = new double[n + 1];
            for (int i = 0; i <= n; i++) { sortedS[i] = simplex[order[i]]; sortedV[i] = values[order[i]]; }
            simplex = sortedS; values = sortedV;

            // Convergence: spread of objective values across the simplex is tiny.
            if (Math.abs(values[n] - values[0]) <= tolerance * (Math.abs(values[0]) + tolerance)) {
                break;
            }

            // Centroid of all but the worst vertex.
            double[] centroid = new double[n];
            for (int i = 0; i < n; i++) {
                double sum = 0.0;
                for (int j = 0; j < n; j++) sum += simplex[j][i];
                centroid[i] = sum / n;
            }

            // Reflection.
            double[] reflected = clamp(combine(centroid, simplex[n], alpha), lower, upper);
            double reflVal = objective.apply(reflected);

            if (reflVal < values[0]) {
                // Expansion.
                double[] expanded = clamp(combine(centroid, simplex[n], alpha * gamma), lower, upper);
                double expVal = objective.apply(expanded);
                if (expVal < reflVal) { simplex[n] = expanded; values[n] = expVal; }
                else                  { simplex[n] = reflected; values[n] = reflVal; }
            } else if (reflVal < values[n - 1]) {
                simplex[n] = reflected; values[n] = reflVal;
            } else {
                // Contraction toward the better of (reflected, worst).
                double[] contracted;
                double contractVal;
                if (reflVal < values[n]) {
                    contracted = clamp(combine(centroid, simplex[n], alpha * rho), lower, upper);
                } else {
                    contracted = clamp(shrinkToward(centroid, simplex[n], rho), lower, upper);
                }
                contractVal = objective.apply(contracted);
                double worst = reflVal < values[n] ? reflVal : values[n];
                if (contractVal < worst) {
                    simplex[n] = contracted; values[n] = contractVal;
                } else {
                    // Shrink the whole simplex toward the best vertex.
                    for (int i = 1; i <= n; i++) {
                        simplex[i] = clamp(shrinkToward(simplex[0], simplex[i], sigma), lower, upper);
                        values[i] = objective.apply(simplex[i]);
                    }
                }
            }
        }

        // Return the current best vertex.
        int best = 0;
        for (int i = 1; i <= n; i++) if (values[i] < values[best]) best = i;
        return new Result(simplex[best].clone(), values[best], iter);
    }

    /** centroid + coeff·(centroid − worst) — reflection/expansion move. */
    private static double[] combine(double[] centroid, double[] worst, double coeff) {
        double[] r = new double[centroid.length];
        for (int i = 0; i < r.length; i++) r[i] = centroid[i] + coeff * (centroid[i] - worst[i]);
        return r;
    }

    /** point moved a fraction {@code t} from {@code from} toward {@code target}. */
    private static double[] shrinkToward(double[] from, double[] target, double t) {
        double[] r = new double[from.length];
        for (int i = 0; i < r.length; i++) r[i] = from[i] + t * (target[i] - from[i]);
        return r;
    }

    private static double[] clamp(double[] p, double[] lower, double[] upper) {
        for (int i = 0; i < p.length; i++) {
            if (p[i] < lower[i]) p[i] = lower[i];
            else if (p[i] > upper[i]) p[i] = upper[i];
        }
        return p;
    }
}
