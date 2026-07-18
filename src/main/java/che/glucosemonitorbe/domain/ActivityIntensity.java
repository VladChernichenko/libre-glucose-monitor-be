package che.glucosemonitorbe.domain;

/**
 * User-chosen activity intensity for a logged activity note, and its mapping to the model's
 * normalized activity signal {@code a(t) ∈ [0,1]}.
 */
public enum ActivityIntensity {
    LOW(0.25),
    MODERATE(0.5),
    HIGH(0.75),
    VERY_HARD(1.0);

    private final double aLevel;

    ActivityIntensity(double aLevel) {
        this.aLevel = aLevel;
    }

    /** The normalized activity intensity {@code a(t) ∈ [0,1]} this level maps to. */
    public double aLevel() {
        return aLevel;
    }

    /** Parse a stored/requested level name, or null if not a recognised level. */
    public static ActivityIntensity fromName(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
