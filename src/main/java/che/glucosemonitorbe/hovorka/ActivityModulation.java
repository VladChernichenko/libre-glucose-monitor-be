package che.glucosemonitorbe.hovorka;

/**
 * Turns a normalized activity intensity {@code a(t) ∈ [0,1]} into the two coupled effects exercise has
 * on glucose dynamics, from a single intensity:
 *
 * <ul>
 *   <li><b>Insulin-sensitivity amplification</b> — insulin action is scaled by
 *       {@code (1 + gainInsulin·aSens)}. This carries a <b>post-exercise tail</b>: sensitivity stays
 *       elevated after activity ends, decaying with a configurable half-life (a leading cause of
 *       delayed post-exercise hypoglycemia).</li>
 *   <li><b>Insulin-independent uptake</b> — a first-order glucose clearance {@code gainIndep·aInst}
 *       [per min] that acts on the <em>instantaneous</em> intensity only (contraction-mediated uptake
 *       during exercise), so a drop occurs even when IOB ≈ 0.</li>
 * </ul>
 *
 * <p>Stateful: the tail is a reservoir that charges to the current intensity and decays each minute.
 * One instance per prediction run. Pure math, no Spring — unit-testable.</p>
 */
public final class ActivityModulation {

    /** Insulin-action multiplier at full (tailed) intensity: {@code 1 + GAIN_INSULIN} at aSens = 1. */
    public static final double GAIN_INSULIN = 1.0;
    /** Insulin-independent glucose clearance [per min] at full instantaneous intensity (small by design). */
    public static final double GAIN_INDEP = 0.005;
    /** Half-life of the post-exercise sensitivity tail [min]. */
    public static final double TAIL_HALF_LIFE_MIN = 120.0;
    /** How far before "now" to warm-start the tail reservoir from prior activity [min] (~3 half-lives). */
    public static final int WARMUP_MINUTES = 360;

    private final double gainInsulin;
    private final double gainIndep;
    private final double decayPerMin;
    private double reservoir = 0.0;

    public ActivityModulation() {
        this(GAIN_INSULIN, GAIN_INDEP, TAIL_HALF_LIFE_MIN);
    }

    public ActivityModulation(double gainInsulin, double gainIndep, double tailHalfLifeMin) {
        this.gainInsulin = gainInsulin;
        this.gainIndep = gainIndep;
        this.decayPerMin = Math.pow(0.5, 1.0 / tailHalfLifeMin);
    }

    /** Clamp a raw intensity into {@code [0,1]}; NaN → 0. */
    public static double clampIntensity(double a) {
        if (Double.isNaN(a)) return 0.0;
        return Math.max(0.0, Math.min(1.0, a));
    }

    /**
     * Advance the post-exercise tail reservoir by one minute with the given instantaneous intensity and
     * return the effective sensitivity intensity: the reservoir tracks activity while it happens, then
     * decays with the configured half-life once it stops.
     */
    public double stepSensitivity(double aInst) {
        reservoir = Math.max(reservoir * decayPerMin, clampIntensity(aInst));
        return reservoir;
    }

    /** Multiplier applied to the insulin effect for a (tailed) sensitivity intensity {@code aSens}. */
    public double insulinSensitivityFactor(double aSens) {
        return 1.0 + gainInsulin * clampIntensity(aSens);
    }

    /** Insulin-independent fractional glucose clearance [per min] for the instantaneous intensity. */
    public double uptakeRate(double aInst) {
        return gainIndep * clampIntensity(aInst);
    }
}
