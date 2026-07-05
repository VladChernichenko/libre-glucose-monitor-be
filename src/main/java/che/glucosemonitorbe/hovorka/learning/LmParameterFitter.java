package che.glucosemonitorbe.hovorka.learning;

import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction;
import org.apache.commons.math3.exception.MaxCountExceededException;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

/**
 * Levenberg–Marquardt (LM) nonlinear least-squares fitter for the digital-twin's inverse-dynamics
 * parameter estimation. Wraps Apache Commons Math's {@link LevenbergMarquardtOptimizer} so the rest
 * of the calibration code can fit an arbitrary physiological parameter vector by minimising
 *
 * <pre>{@code   J(θ) = Σ_i ( G_model(t_i; θ) − G_real(t_i) )²  }</pre>
 *
 * i.e. the squared error between the RK4-integrated Hovorka prediction and the real CGM trace at a
 * set of sample points. LM is the recommended optimiser for this class of problem: it interpolates
 * between Gauss–Newton (fast near the optimum) and gradient descent (robust far from it), which suits
 * the mild parameter coupling of a physiological ODE (insulin sensitivity, EGP₀, absorption time all
 * reshape the same post-meal curve).
 *
 * <h3>Derivatives</h3>
 * <p>The forward model is an ODE integration with no closed-form Jacobian, so ∂residual/∂θ is
 * approximated by forward finite differences. The number of residuals must be constant across
 * evaluations (it is — the sample set is fixed by the CGM/anchor grid, independent of θ).</p>
 *
 * <h3>Hard clamping</h3>
 * <p>LM is unconstrained by construction. Physiological bounds (e.g. sensitivity or EGP₀ can never be
 * negative) are enforced as a {@link LeastSquaresBuilder#parameterValidator parameter validator} that
 * projects every trial θ back into {@code [lower, upper]} before the model is evaluated — the
 * "software clamping" the estimation must never violate.</p>
 *
 * <p>Pure math, no Spring/DB dependencies, so it is unit-testable against synthetic models.</p>
 */
public final class LmParameterFitter {

    /**
     * The forward model as seen by the optimiser: given a parameter vector, return the residual
     * {@code G_model − G_real} at each sample point. Residual length must not depend on {@code params}.
     */
    @FunctionalInterface
    public interface ResidualModel {
        double[] residuals(double[] params);
    }

    /** Outcome of a fit. */
    public record Result(double[] params, double rmse, double cost, int iterations, int evaluations) {}

    /** Relative finite-difference step for the numerical Jacobian. */
    private final double fdRelStep;
    private final int maxIterations;
    private final int maxEvaluations;

    public LmParameterFitter() {
        this(1e-4, 100, 2000);
    }

    public LmParameterFitter(double fdRelStep, int maxIterations, int maxEvaluations) {
        this.fdRelStep = fdRelStep;
        this.maxIterations = maxIterations;
        this.maxEvaluations = maxEvaluations;
    }

    /**
     * Fit {@code model} starting from {@code start}, keeping every parameter within
     * {@code [lower[i], upper[i]]}.
     *
     * @param model  residual function (θ → residual vector)
     * @param start  initial parameter guess (e.g. all scales = 1.0 — the "warm start" toward physiology)
     * @param lower  per-parameter lower bounds (hard clamp)
     * @param upper  per-parameter upper bounds (hard clamp)
     * @return the fitted parameters (already clamped) plus fit diagnostics
     */
    public Result fit(ResidualModel model, double[] start, double[] lower, double[] upper) {
        if (start.length != lower.length || start.length != upper.length) {
            throw new IllegalArgumentException("start/lower/upper length mismatch");
        }
        double[] start0 = clampToBounds(start.clone(), lower, upper);

        // Residual length is fixed by the sample grid; probe it once at the start point.
        int residualCount = model.residuals(start0).length;

        MultivariateJacobianFunction jacobianModel = params -> {
            double[] theta = params.toArray();
            double[] r0 = model.residuals(theta);
            RealVector value = new ArrayRealVector(r0, false);

            double[][] jac = new double[r0.length][theta.length];
            for (int j = 0; j < theta.length; j++) {
                double h = fdRelStep * Math.max(1e-3, Math.abs(theta[j]));
                double[] perturbed = theta.clone();
                perturbed[j] += h;
                double[] rj = model.residuals(perturbed);
                for (int i = 0; i < r0.length; i++) {
                    jac[i][j] = (rj[i] - r0[i]) / h;
                }
            }
            return new Pair<>(value, (RealMatrix) new Array2DRowRealMatrix(jac, false));
        };

        LeastSquaresProblem problem = new LeastSquaresBuilder()
                .start(start0)
                .model(jacobianModel)
                .target(new double[residualCount])          // drive residuals → 0
                .lazyEvaluation(false)
                .maxIterations(maxIterations)
                .maxEvaluations(maxEvaluations)
                .parameterValidator(params ->                // hard clamp every trial point
                        new ArrayRealVector(clampToBounds(params.toArray(), lower, upper), false))
                .build();

        try {
            LeastSquaresOptimizer.Optimum opt = new LevenbergMarquardtOptimizer().optimize(problem);

            double[] fitted = clampToBounds(opt.getPoint().toArray(), lower, upper);
            double rmse = opt.getRMS();
            double cost = opt.getCost();                      // ½·Σ residual² in Commons Math
            return new Result(fitted, rmse, cost, opt.getIterations(), opt.getEvaluations());
        } catch (MaxCountExceededException e) {
            // LM could not converge within the iteration/evaluation budget (flat or ill-conditioned
            // objective for this data). Degrade gracefully to the start point instead of throwing, so a
            // hard-to-fit user falls back to "no personalisation" rather than failing the whole run.
            double[] fallback = clampToBounds(start0.clone(), lower, upper);
            return new Result(fallback, Double.NaN, Double.NaN, maxIterations, maxEvaluations);
        }
    }

    /** Project a parameter vector into {@code [lower, upper]} element-wise. */
    static double[] clampToBounds(double[] p, double[] lower, double[] upper) {
        for (int i = 0; i < p.length; i++) {
            if (Double.isNaN(p[i])) p[i] = (lower[i] + upper[i]) / 2.0;
            else if (p[i] < lower[i]) p[i] = lower[i];
            else if (p[i] > upper[i]) p[i] = upper[i];
        }
        return p;
    }
}
