package che.glucosemonitorbe.hovorka.learning;

/**
 * Per-user multiplicative corrections ("scales") learned by the digital-twin calibrator and
 * applied on top of the population-derived {@link che.glucosemonitorbe.hovorka.HovorkaParameters}.
 *
 * <p>Scales are centred on <b>1.0</b> (= no correction). Learning a scale rather than an absolute
 * value keeps the calibration regularised toward physiology and means the base value can still come
 * from the user's measured ISF / settings - the twin only nudges it to fit the CGM record.</p>
 *
 * <h3>Active dimensions (v1)</h3>
 * <ul>
 *   <li>{@code isfScale} - corrects systematic under/over-response to insulin. Applied per
 *       dose by {@code HovorkaGlucosePredictionService}, on top of whichever ISF (settings
 *       meal-window override, settings ISF or parameter fallback) the dose resolves to.</li>
 *   <li>{@code agScale} - corrects systematic meal-magnitude bias (carbs hitting harder/softer than
 *       logged). Flows through {@code HovorkaParameters.aG()}.</li>
 *   <li>{@code egpScale} - corrects fasting/basal drift. Fitted on fasting anchors and applied to
 *       EGP0 by {@code HovorkaParameterService.applyScales}; it reaches the forecast when a basal
 *       dose is active. The fasting drift it does not remove falls to the
 *       {@link ResidualBiasModel}, which is fitted on the scaled model's remaining error.</li>
 * </ul>
 *
 * <h3>Reserved dimensions</h3>
 * <p>{@code tMaxGScale} (absorption speed) is carried so the entity and optimiser vector can grow,
 * but is <b>not</b> wired into the live ODE: the gut time constant is adjusted per meal (multiplied
 * by the fiber factor, replaced for a rescue carb). It defaults to 1.0 (neutral).</p>
 *
 * @param isfScale    multiplier on ISF               [dimensionless, ~0.5-2.0]
 * @param agScale     multiplier on meal magnitude A_G [dimensionless, ~0.5-2.0]
 * @param tMaxGScale  multiplier on gut absorption time (reserved)
 * @param egpScale    multiplier on endogenous glucose production
 */
public record TwinScales(
        double isfScale,
        double agScale,
        double tMaxGScale,
        double egpScale
) {
    /** Lower bound for any scale - prevents the optimiser wandering to unphysical extremes. */
    public static final double MIN_SCALE = 0.5;
    /** Upper bound for any scale. */
    public static final double MAX_SCALE = 2.0;

    /** The neutral twin: every scale 1.0, i.e. predictions identical to the un-calibrated model. */
    public static TwinScales neutral() {
        return new TwinScales(1.0, 1.0, 1.0, 1.0);
    }

    /** Convenience factory for {@code isfScale} and {@code agScale}; the other two scales stay neutral. */
    public static TwinScales of(double isfScale, double agScale) {
        return new TwinScales(isfScale, agScale, 1.0, 1.0);
    }

    /** Clamp a single scale into the physiological band {@code [MIN_SCALE, MAX_SCALE]}. */
    public static double clamp(double scale) {
        if (Double.isNaN(scale) || Double.isInfinite(scale)) return 1.0;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /** Returns a copy with every scale clamped into the physiological band. */
    public TwinScales clamped() {
        return new TwinScales(clamp(isfScale), clamp(agScale), clamp(tMaxGScale), clamp(egpScale));
    }
}
