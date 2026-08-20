package che.glucosemonitorbe.domain;

/**
 * The absorption profile of a fast-acting rescue carb (glucose gel, dextrose tablets, juice)
 * taken to treat a hypo.
 *
 * <p>A rescue carb is pure glucose and is clinically done inside half an hour. Running it through
 * the user's mixed-meal curve - a 45-minute half-life over a 4-hour window - would leave phantom
 * carbs on board long after the glucose had actually been absorbed, inflating both the displayed
 * COB and every prediction drawn from it.
 *
 * <p>These constants are the single definition shared by {@code CarbsOnBoardService} and the
 * Hovorka prediction path. Do not re-declare them: this repo has twice had an absorption curve
 * hand-copied into a second code path and silently drift.
 */
public final class RescueCarbProfile {

    private RescueCarbProfile() {}

    /** Marker written to {@link CarbsEntry#getAbsorptionMode()} for a rescue carb. */
    public static final String ABSORPTION_MODE = "RESCUE";

    /** Exponential half-life [min]. Glucose gel is substantially absorbed within 15 minutes. */
    public static final int HALF_LIFE_MIN = 15;

    /** Absorption is complete by this point [min]; COB is exactly zero from here on. */
    public static final int MAX_DURATION_MIN = 45;

    /** Minutes over which the tail tapers linearly to zero, so COB does not snap. */
    public static final int TAPER_MIN = 15;

    /** Glycemic index of pure glucose. */
    public static final int GI = 100;

    /** True when {@code absorptionMode} marks an entry as a rescue carb. */
    public static boolean isRescue(String absorptionMode) {
        return ABSORPTION_MODE.equalsIgnoreCase(absorptionMode);
    }
}
