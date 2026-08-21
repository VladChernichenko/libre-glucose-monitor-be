package che.glucosemonitorbe.service.observer;

/**
 * The glucose thresholds that define a hypo, shared by every path that needs them.
 *
 * <p>These were previously private to {@link GlucoseAlertEvaluator}. A second copy elsewhere would
 * make "is this a hypo" answerable two ways, which is the failure mode this codebase has already
 * paid for once (see the dual-computation audit of 2026-08-08).
 */
public final class HypoThresholds {

    private HypoThresholds() {}

    /** ADA/ATTD Level 1 hypoglycaemia [mmol/L]. At or above this is not a hypo. */
    public static final double HYPO_MMOL = 3.9;

    /**
     * Glucose must reach this before an open hypo is considered resolved [mmol/L].
     *
     * <p>The gap above {@link #HYPO_MMOL} is deliberate hysteresis: a reading hovering on the
     * threshold would otherwise open and close the prompt on alternate scans.
     */
    public static final double RECOVERY_MMOL = 4.5;

    /**
     * How long after resolving one hypo event before another may open [min].
     *
     * <p>Matches the clinical "rule of 15" - treat with 15 g, recheck after 15 minutes, re-treat if
     * still low. Without it, resolving an event leaves no OPEN row and the next 5-minute scan would
     * immediately re-open while the user is still low.
     */
    public static final int SUPPRESSION_MINUTES = 15;
}
