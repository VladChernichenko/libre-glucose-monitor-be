package che.glucosemonitorbe.hovorka;

/**
 * Per-user activity parameters: a scale on the insulin-sensitivity gain and the half-life of the
 * post-exercise sensitivity tail. The insulin-independent gain stays fixed.
 */
public record ActivityGains(double gainScale, double tailHalfLifeMin) {

    public static final double MIN_GAIN_SCALE = 0.5;
    public static final double MAX_GAIN_SCALE = 3.0;
    public static final double MIN_HALF_LIFE_MIN = 60.0;
    public static final double MAX_HALF_LIFE_MIN = 360.0;

    /** Population defaults: reproduces the un-personalised model exactly. */
    public static final ActivityGains NEUTRAL =
            new ActivityGains(1.0, ActivityModulation.TAIL_HALF_LIFE_MIN);

    public ActivityGains clamped() {
        return new ActivityGains(
                Math.max(MIN_GAIN_SCALE, Math.min(MAX_GAIN_SCALE, gainScale)),
                Math.max(MIN_HALF_LIFE_MIN, Math.min(MAX_HALF_LIFE_MIN, tailHalfLifeMin)));
    }

    public ActivityModulation modulation() {
        return new ActivityModulation(
                ActivityModulation.GAIN_INSULIN * gainScale,
                ActivityModulation.GAIN_INDEP,
                tailHalfLifeMin);
    }
}
