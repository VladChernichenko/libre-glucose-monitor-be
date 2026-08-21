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

    /** Absorption speed class a rescue carb reports to the COB duration table. */
    public static final String SPEED_CLASS = "FAST";

    /** True when {@code absorptionMode} marks an entry as a rescue carb. */
    public static boolean isRescue(String absorptionMode) {
        return ABSORPTION_MODE.equalsIgnoreCase(absorptionMode);
    }

    /**
     * Stamp the rescue profile onto {@code entry}. <b>The one place the marker is written.</b>
     *
     * <p>Every consumer that turns a {@code hypo_treatment} note into a {@link CarbsEntry} must go
     * through here. Four separate code paths build a {@code CarbsEntry} - the shared note mapper,
     * the twin-replay engine, the unlogged-event detector and the AI context aggregator - and three
     * of them once built it by hand without an {@code absorptionMode}, so {@link #isRescue} was
     * false and the rescue silently reverted to the user's 240-minute mixed-meal curve. In the twin
     * fit that is not merely cosmetic: the model saw a sharp real glucose rise it could not explain
     * and compensated by moving {@code agScale}/{@code isfScale}, which then applied to every
     * prediction for that user - exactly the titration corruption a rescue is supposed to be immune
     * to.
     *
     * @return the same instance, for chaining
     */
    public static CarbsEntry mark(CarbsEntry entry) {
        entry.setAbsorptionMode(ABSORPTION_MODE);
        entry.setEstimatedGi((double) GI);
        entry.setAbsorptionSpeedClass(SPEED_CLASS);
        return entry;
    }
}
