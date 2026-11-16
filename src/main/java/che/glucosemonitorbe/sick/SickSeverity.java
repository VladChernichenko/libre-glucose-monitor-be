package che.glucosemonitorbe.sick;

import java.util.Optional;

/** Illness severity levels. SEVERE_UNRESPONSIVE is reachable only by accepting a step-up proposal. */
public enum SickSeverity {
    MILD(0.85, 0.0),
    MODERATE(0.70, 0.15),
    SEVERE(0.50, 0.30),
    SEVERE_UNRESPONSIVE(0.40, 0.30);

    private final double isfMultiplier;
    private final double basalNeed;

    SickSeverity(double isfMultiplier, double basalNeed) {
        this.isfMultiplier = isfMultiplier;
        this.basalNeed = basalNeed;
    }

    public double isfMultiplier() {
        return isfMultiplier;
    }

    public double basalNeed() {
        return basalNeed;
    }

    public Optional<SickSeverity> next() {
        return switch (this) {
            case MILD -> Optional.of(MODERATE);
            case MODERATE -> Optional.of(SEVERE);
            case SEVERE -> Optional.of(SEVERE_UNRESPONSIVE);
            case SEVERE_UNRESPONSIVE -> Optional.empty();
        };
    }

    public boolean selectable() {
        return this != SEVERE_UNRESPONSIVE;
    }
}
