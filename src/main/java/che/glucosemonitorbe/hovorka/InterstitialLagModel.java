package che.glucosemonitorbe.hovorka;

/**
 * Measurement model for the CGM: interstitial glucose lagging plasma glucose.
 *
 * <p>The ODE integrates <b>plasma</b> glucose, but a CGM samples interstitial fluid, which trails
 * plasma as glucose diffuses across the capillary wall.</p>
 *
 * <pre>
 *   dGi/dt = (G - Gi) / tau
 * </pre>
 *
 * <p>Advanced with the exact one-minute discretisation {@code alpha = 1 - exp(-1/tau)} rather than a
 * forward Euler step, so the filter stays accurate and unconditionally stable for any tau.</p>
 *
 * <h3>The lag runs both ways</h3>
 * <p>The same equation that turns predicted plasma into a predicted reading also says what plasma
 * is doing <em>now</em>, given a reading and its trend: {@code G = Gi + tau × dGi/dt}
 * ({@link #plasmaFromSensor}). Both halves have to be used together. Starting the ODE from the
 * reading as if it were plasma and then filtering the output applies the lag twice: with glucose
 * rising 0.1 mmol/L per minute the model would start most of a mmol/L below the true plasma value
 * and then delay its own answer on top. With the trend unknown (0) the two coincide, which is the
 * old behaviour.</p>
 *
 * <h3>Why tau is pinned to physiology and not fitted</h3>
 * <p>Tracer studies put the plasma-to-interstitial delay at 5-6 min in healthy adults and a median
 * of 6.8 min (range 4.8-9.8) in adults with type 1 diabetes (Basu et al., Diabetes 2013,
 * doi:10.2337/db13-1132; J Diabetes Sci Technol 2015, doi:10.1177/1932296814554797). tau is set to
 * 7 min on that basis.</p>
 *
 * <p>It is deliberately not tuned for accuracy. Sweeping tau on the held-out AZT1D record (see
 * {@code Azt1dCalibrationValidationTest#interstitialLagSweep}) shows skill-vs-persistence rising
 * monotonically all the way to 100 minutes. That is not evidence for a long lag: as tau grows the
 * filter mutes the model's own dynamics and the prediction slides toward the anchor, i.e. toward
 * persistence itself - RMSE bought by refusing to move, which is worthless for seeing a meal or a
 * hypo coming.</p>
 *
 * <h3>Configuration</h3>
 * <p>Override with {@code -Dhovorka.cgmLagMin=...}; {@code 0} disables the measurement model in
 * both directions and makes predictions identical to the un-lagged model.</p>
 */
public final class InterstitialLagModel {

    /** Physiological interstitial-to-plasma equilibration time [min]. */
    public static final double PHYSIOLOGICAL_TAU_MIN = 7.0;

    /**
     * Largest sensor trend [mmol/L per min] used to infer plasma. A CGM trend is a noisy difference
     * of noisy readings; beyond this it is more likely an artefact than a real rate of change.
     */
    public static final double MAX_TREND_MMOL_PER_MIN = 0.15;

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
     * Plasma glucose [mmol/L] implied by a sensor reading and its trend:
     * {@code G = Gi + tau × dGi/dt}, with the trend limited to ±{@link #MAX_TREND_MMOL_PER_MIN}.
     * Returns the reading unchanged when the trend is 0 or not finite, or the lag is disabled.
     */
    public static double plasmaFromSensor(double sensedMmolL, double trendMmolPerMin) {
        double tau = tauMinutes;
        if (tau <= 0 || !Double.isFinite(trendMmolPerMin)) return sensedMmolL;
        double trend = Math.max(-MAX_TREND_MMOL_PER_MIN, Math.min(MAX_TREND_MMOL_PER_MIN, trendMmolPerMin));
        return sensedMmolL + tau * trend;
    }

    /** A filter whose sensed value starts at {@code g0} - the reading at the anchor. */
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
