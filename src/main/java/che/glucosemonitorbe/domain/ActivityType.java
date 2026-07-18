package che.glucosemonitorbe.domain;

/**
 * Type of a logged activity. Stored for analysis and future per-type learning; it does not change the
 * glucose ODE this round (intensity + duration drive the effect).
 */
public enum ActivityType {
    WALKING,
    RUNNING,
    CYCLING,
    STRENGTH,
    OTHER;

    /** Parse a stored/requested type name, or null if not a recognised type. */
    public static ActivityType fromName(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
