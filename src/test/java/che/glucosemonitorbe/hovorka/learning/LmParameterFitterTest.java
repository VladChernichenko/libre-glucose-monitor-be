package che.glucosemonitorbe.hovorka.learning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Levenberg–Marquardt fitter against synthetic models — no ODE/DB needed.
 */
class LmParameterFitterTest {

    private final LmParameterFitter fitter = new LmParameterFitter();

    @Test
    @DisplayName("recovers the parameters of a nonlinear model y = a·exp(b·x) from clean data")
    void recoversNonlinearParameters() {
        double trueA = 2.5, trueB = -0.35;
        double[] xs = {0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0};
        double[] ys = new double[xs.length];
        for (int i = 0; i < xs.length; i++) ys[i] = trueA * Math.exp(trueB * xs[i]);

        LmParameterFitter.ResidualModel model = p -> {
            double a = p[0], b = p[1];
            double[] r = new double[xs.length];
            for (int i = 0; i < xs.length; i++) r[i] = a * Math.exp(b * xs[i]) - ys[i];
            return r;
        };

        LmParameterFitter.Result res = fitter.fit(
                model,
                new double[]{1.0, 0.0},            // deliberately off starting guess
                new double[]{0.1, -5.0},
                new double[]{10.0, 5.0});

        assertEquals(trueA, res.params()[0], 1e-3, "a should be recovered");
        assertEquals(trueB, res.params()[1], 1e-3, "b should be recovered");
        assertTrue(res.rmse() < 1e-4, "residuals should be driven to ~0, got rmse=" + res.rmse());
    }

    @Test
    @DisplayName("hard-clamps the fitted parameters inside the physiological bounds")
    void clampsToBounds() {
        // True optimum b = -0.35 lies below the lower bound we impose (-0.2); the fit must stop at it.
        double trueA = 2.5, trueB = -0.35;
        double[] xs = {0.0, 1.0, 2.0, 3.0, 4.0, 5.0};
        double[] ys = new double[xs.length];
        for (int i = 0; i < xs.length; i++) ys[i] = trueA * Math.exp(trueB * xs[i]);

        double[] lower = {0.1, -0.2};
        double[] upper = {10.0, 5.0};

        LmParameterFitter.ResidualModel model = p -> {
            double a = p[0], b = p[1];
            double[] r = new double[xs.length];
            for (int i = 0; i < xs.length; i++) r[i] = a * Math.exp(b * xs[i]) - ys[i];
            return r;
        };

        LmParameterFitter.Result res = fitter.fit(model, new double[]{1.0, 0.0}, lower, upper);

        assertTrue(res.params()[0] >= lower[0] && res.params()[0] <= upper[0], "a within bounds");
        assertTrue(res.params()[1] >= lower[1] && res.params()[1] <= upper[1],
                "b must be clamped into bounds, got " + res.params()[1]);
    }

    @Test
    @DisplayName("a NaN parameter is projected back to the mid-bound instead of propagating")
    void clampHandlesNaN() {
        double[] out = LmParameterFitter.clampToBounds(
                new double[]{Double.NaN, 3.0, -7.0},
                new double[]{0.5, 0.5, 0.5},
                new double[]{2.0, 2.0, 2.0});
        assertEquals(1.25, out[0], 1e-9, "NaN → mid-bound");
        assertEquals(2.0, out[1], 1e-9, "over-upper → upper");
        assertEquals(0.5, out[2], 1e-9, "under-lower → lower");
    }
}
