package che.glucosemonitorbe.hovorka.learning;

/**
 * Per-user multiplicative corrections ("scales") learned by the digital-twin calibrator and
 * applied on top of the population/experiment-derived {@link che.glucosemonitorbe.hovorka.HovorkaParameters}.
 *
 * <p>Scales are centred on <b>1.0</b> (= no correction). Learning a scale rather than an absolute
 * value keeps the calibration regularised toward physiology and means the base value can still come
 * from the user's measured ISF / settings — the twin only nudges it to fit the CGM record.</p>
 *
 * <h3>Active dimensions (v1)</h3>
 * <ul>
 *   <li>{@code isfScale} — corrects systematic under/over-response to insulin. Flows to every
 *       prediction path through {@code HovorkaParameters.isf()}.</li>
 *   <li>{@code agScale} — corrects systematic meal-magnitude bias (carbs hitting harder/softer than
 *       logged). Flows through {@code HovorkaParameters.aG()}.</li>
 * </ul>
 *
 * <h3>Reserved dimensions</h3>
 * <p>{@code tMaxGScale} (absorption speed) and {@code egpScale} (fasting/basal drift) are carried so
 * the entity and optimiser vector can grow to the full digital twin, but are <b>not</b> wired into the
 * live ODE in v1: {@code tMaxG} is frequently overridden per-meal by
 * {@link che.glucosemonitorbe.hovorka.MacroNutrientGastricModel}, and fasting drift is currently
 * absorbed more robustly by the {@link ResidualBiasModel}. They default to 1.0 (neutral).</p>
 *
 * @param isfScale    multiplier on ISF               [dimensionless, ~0.5–2.0]
 * @param agScale     multiplier on meal magnitude A_G [dimensionless, ~0.5–2.0]
 * @param tMaxGScale  multiplier on gut absorption time (reserved)
 * @param egpScale    multiplier on endogenous glucose production (reserved)
 */
public record TwinScales(
        double isfScale,
        double agScale,
        double tMaxGScale,
        double egpScale
) {
    /** Lower bound for any scale — prevents the optimiser wandering to unphysical extremes. */
    public static final double MIN_SCALE = 0.5;
    /** Upper bound for any scale. */
    public static final double MAX_SCALE = 2.0;

    /** The neutral twin: every scale 1.0, i.e. predictions identical to the un-calibrated model. */
    public static TwinScales neutral() {
        return new TwinScales(1.0, 1.0, 1.0, 1.0);
    }

    /** Convenience factory for the two v1-active dimensions (reserved scales left neutral). */
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
