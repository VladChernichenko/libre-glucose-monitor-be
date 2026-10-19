package che.glucosemonitorbe.domain;

/**
 * Where an insulin dose was injected ({@code notes.site_zone}, spec F7). Stored and sent as the
 * lowercase {@link #code()}, e.g. {@code abd_ur}. Left/right are the user's own sides.
 */
public enum InjectionSiteZone {
    ABD_UR("abd_ur", "Belly, upper right"),
    ABD_UL("abd_ul", "Belly, upper left"),
    ABD_LR("abd_lr", "Belly, lower right"),
    ABD_LL("abd_ll", "Belly, lower left"),
    THIGH_R("thigh_r", "Thigh, right front"),
    THIGH_L("thigh_l", "Thigh, left front"),
    ARM_R("arm_r", "Arm, right (back)"),
    ARM_L("arm_l", "Arm, left (back)"),
    FLANK_R("flank_r", "Flank, right"),
    FLANK_L("flank_l", "Flank, left"),
    BUTT_R("butt_r", "Buttock, right upper-outer"),
    BUTT_L("butt_l", "Buttock, left upper-outer"),
    /** The user doesn't remember; a valid answer that counts for no zone. */
    UNKNOWN("unknown", "Don't remember");

    private final String code;
    private final String label;

    InjectionSiteZone(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() { return code; }

    public String label() { return label; }

    /** Buttocks absorb slowly, so they are never suggested for rapid-acting insulin. */
    public boolean isButtock() { return this == BUTT_R || this == BUTT_L; }

    /** A real body zone (everything but {@link #UNKNOWN}). */
    public boolean isBodyZone() { return this != UNKNOWN; }

    /** Parse a stored/requested code, or null if not a recognised zone. */
    public static InjectionSiteZone fromCode(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        for (InjectionSiteZone z : values()) {
            if (z.code.equals(c)) return z;
        }
        return null;
    }
}
