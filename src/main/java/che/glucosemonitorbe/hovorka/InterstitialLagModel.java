package che.glucosemonitorbe.hovorka;

/**
 * Measurement model for the CGM: interstitial glucose lagging plasma glucose.
 *
 * <p>The ODE integrates <b>plasma</b> glucose, but a CGM samples interstitial fluid, which trails
 * plasma by roughly 10-15 minutes as glucose diffuses across the capillary wall. Comparing the
 * un-lagged model output directly against CGM therefore charges the model for an error the sensor
 * introduced - worst at short horizons, where the whole prediction is dominated by the leading edge
 * of a rise.</p>
 *
 * <pre>
 *   dGi/dt = (G - Gi) / tau
 * </pre>
 *
 * <p>Advanced with the exact one-minute discretisation {@code alpha = 1 - exp(-1/tau)} rather than a
 * forward Euler step, so the filter stays accurate and unconditionally stable for any tau.</p>
 *
 * <h3>Why tau is pinned to physiology and not fitted</h3>
 * <p>Sweeping tau on the held-out AZT1D record (see {@code Azt1dCalibrationValidationTest
 * #interstitialLagSweep}) shows skill-vs-persistence rising <b>monotonically all the way to 100
 * minutes</b> - far past any plausible sensor lag. That is not evidence for a long lag: as tau grows
 * the filter increasingly mutes the model's own dynamics and the prediction slides toward the anchor,
 * i.e. toward persistence itself. Chasing that curve would buy RMSE by making the prediction refuse
 * to move, which is worthless for the thing predictions are for - seeing a meal or a hypo coming.</p>
 *
 * <p>So tau is set to the physiological 12 min and left there. The honest reading of the sweep is a
 * finding about the <i>rest</i> of the model, not about the sensor: the excursions it predicts are
 * currently worse than predicting no excursion at all.</p>
 *
 * <h3>Configuration</h3>
 * <p>Override with {@code -Dhovorka.cgmLagMin=...}; {@code 0} disables the filter and makes
 * predictions bit-identical to the un-lagged model.</p>
 */
public final class InterstitialLagModel {

    /** Physiological interstitial-to-plasma equilibration time [min]. */
    public static final double PHYSIOLOGICAL_TAU_MIN = 12.0;

    /** Interstitial time constant [min]; 0 disables the filter. Global model configuration. */
    public static volatile double tauMinutes = Double.parseDouble(
            System.getProperty("hovorka.cgmLagMin", Double.toString(PHYSIOLOGICAL_TAU_MIN)));

    private final double alpha;
    private double gi;

    private InterstitialLagModel(double tau, double g0) {
        this.alpha = tau > 0 ? 1.0 - Math.exp(-1.0 / tau) : 1.0;
        this.gi = g0;
    }

    /**
     * A filter seeded at the anchor glucose. At the anchor the subject is treated as being in
     * quasi-equilibrium, so interstitial equals plasma there and the lag only builds up as the
     * prediction moves away from the anchor.
     */
    public static InterstitialLagModel startingAt(double g0) {
        return new InterstitialLagModel(tauMinutes, g0);
    }

    /** Advance one minute with the current plasma glucose; returns the sensed (interstitial) value. */
    public double step(double plasmaMmolL) {
        gi += alpha * (plasmaMmolL - gi);
        return gi;
    }

    /** Current sensed value [mmol/L]. */
    public double sensed() {
        return gi;
    }

    /** True when the filter is a pass-through (tau = 0). */
    public boolean isDisabled() {
        return alpha >= 1.0;
    }
}
