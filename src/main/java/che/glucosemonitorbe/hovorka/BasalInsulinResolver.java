package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.entity.Note;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Computes EGP (endogenous glucose production) suppression fraction from long-acting insulin.
 *
 * <h3>Physiology</h3>
 * At therapeutic dosing, basal insulin suppresses roughly 40% of EGP, exactly balanced
 * by non-insulin-dependent glucose utilisation (F01) at fasting glucose.
 *
 * <p>At steady state:  EGP_net = EGP0 × (1 - x3_ss) = F01_abs</p>
 *
 * <h3>EGP suppression curve</h3>
 * <ul>
 *   <li>0 -> duration: full suppression maintained (plateau)</li>
 *   <li>duration -> duration + {@link #WANE_HOURS}: the dose is overdue (linear taper)</li>
 * </ul>
 * <p>{@code duration} is the duration of action of the user's own long-acting insulin, from the
 * insulin catalog (24 h for glargine, 42 h for degludec). A user who injects on schedule therefore
 * never leaves the plateau; a fixed clock made every once-daily user "wane" before each dose.</p>
 *
 * <p>There is deliberately no ramp-up phase: a freshly logged dose is assumed to
 * continue the steady-state coverage already established by the user's regular
 * dosing routine. Without this, logging today's dose a few minutes ago would make the model
 * <em>more</em> pessimistic about EGP suppression than logging nothing at all.</p>
 *
 * <p>Once the newest logged dose is older than {@code duration + WANE_HOURS} the log says nothing
 * about current coverage - an unlogged dose is far likelier than days without basal - so
 * {@link #hasActiveDose} is false and the caller falls back to the same steady-state assumption it
 * uses when no long-acting note exists at all.</p>
 *
 * The peak suppression fraction {@link #PEAK_X3_BASAL} (0.40) is derived from the
 * steady-state identity EGP0 × (1 - x3) = F01 -> x3 = 1 - F01/EGP0 = 1 - 0.0097/0.0161 ≈ 0.40.
 */
@Component
public class BasalInsulinResolver {

    /** EGP suppression fraction at steady-state basal insulin (dimensionless, 0-1). */
    public static final double PEAK_X3_BASAL   = 0.40;

    /** Duration of action assumed when the user's long-acting insulin is unknown [hours]. */
    public static final double DEFAULT_DURATION_HOURS = 24.0;

    /** Hours over which suppression tapers to zero once a dose is past its duration of action. */
    public static final double WANE_HOURS = 8.0;

    private static final double MIN_DURATION_HOURS = 12.0;
    private static final double MAX_DURATION_HOURS = 48.0;

    /** How far back long-acting notes must be loaded to cover any catalog insulin [hours]. */
    public static final int LOOKBACK_HOURS = (int) (MAX_DURATION_HOURS + WANE_HOURS);

    /** {@link #resolveEgpSuppression(List, LocalDateTime, double)} at {@link #DEFAULT_DURATION_HOURS}. */
    public double resolveEgpSuppression(List<Note> longActingNotes, LocalDateTime now) {
        return resolveEgpSuppression(longActingNotes, now, DEFAULT_DURATION_HOURS);
    }

    /**
     * Estimates the current EGP suppression fraction x3_basal ∈ [0, 0.40].
     *
     * <p>If multiple overlapping doses are present, the maximum fraction is taken
     * (conservative - avoid double-counting). Returns 0 if no dose is active; callers must check
     * {@link #hasActiveDose} first, because "no active dose" means the log is silent, not that
     * the user has no basal insulin.</p>
     *
     * @param longActingNotes  notes with {@code isLongActing()==true} from recent history
     * @param now              current time
     * @param durationHours    duration of action of the user's long-acting insulin
     * @return                 x3_basal suppression fraction ∈ [0, PEAK_X3_BASAL]
     */
    public double resolveEgpSuppression(List<Note> longActingNotes, LocalDateTime now, double durationHours) {
        if (longActingNotes == null) {
            return 0.0;
        }
        double maxSuppression = 0.0;
        for (Note note : longActingNotes) {
            double hoursAgo = hoursSinceDose(note, now);
            if (Double.isNaN(hoursAgo)) continue;
            maxSuppression = Math.max(maxSuppression, suppressionCurve(hoursAgo, durationHours));
        }
        return maxSuppression;
    }

    /**
     * True when at least one logged dose is recent enough to say something about current basal
     * coverage: on its plateau or overdue by no more than {@link #WANE_HOURS}.
     */
    public boolean hasActiveDose(List<Note> longActingNotes, LocalDateTime now, double durationHours) {
        if (longActingNotes == null) {
            return false;
        }
        double window = effectiveDuration(durationHours) + WANE_HOURS;
        for (Note note : longActingNotes) {
            double hoursAgo = hoursSinceDose(note, now);
            if (!Double.isNaN(hoursAgo) && hoursAgo <= window) return true;
        }
        return false;
    }

    /** {@link #suppressionCurve(double, double)} at {@link #DEFAULT_DURATION_HOURS}. */
    double suppressionCurve(double hoursAgo) {
        return suppressionCurve(hoursAgo, DEFAULT_DURATION_HOURS);
    }

    /**
     * EGP suppression profile as a function of hours since injection.
     *
     * <pre>
     *   0 -> duration              : plateau at PEAK_X3_BASAL
     *   duration -> duration + WANE: linear PEAK_X3_BASAL -> 0
     *   beyond                     : 0
     * </pre>
     */
    double suppressionCurve(double hoursAgo, double durationHours) {
        double duration = effectiveDuration(durationHours);
        if (hoursAgo < 0 || hoursAgo > duration + WANE_HOURS) return 0.0;
        if (hoursAgo <= duration) {
            return PEAK_X3_BASAL;
        }
        return PEAK_X3_BASAL * (1.0 - (hoursAgo - duration) / WANE_HOURS);
    }

    /**
     * Net EGP [mmol/min] accounting for basal insulin suppression.
     *
     * <p>At full basal coverage, egpNet = f01 (hepatic output = non-insulin-dependent
     * utilisation -> glucose is stable). When basal wanes, egpNet rises above f01,
     * causing fasting hyperglycaemia.</p>
     *
     * @param f01Abs         F01 * weightKg [mmol/min]
     * @param egp0Abs        EGP0 * weightKg [mmol/min] (population: 0.0161 * weight)
     * @param x3Basal        suppression fraction from {@link #resolveEgpSuppression}
     */
    public double netEgp(double f01Abs, double egp0Abs, double x3Basal) {
        // When x3 = PEAK_X3_BASAL ≈ 0.40: EGP_net ≈ EGP0 * 0.60 ≈ 0.0097*W = f01
        // The net contribution to Q1 is: egpNet - f01  (= 0 at steady state)
        // At x3=0 (dose fully overdue): egpNet = EGP0 -> net positive -> glucose rises
        return egp0Abs * (1.0 - x3Basal);
    }

    /** Unknown or implausible durations fall back to the default; the rest are kept in range. */
    private static double effectiveDuration(double durationHours) {
        if (!Double.isFinite(durationHours) || durationHours <= 0) return DEFAULT_DURATION_HOURS;
        return Math.max(MIN_DURATION_HOURS, Math.min(MAX_DURATION_HOURS, durationHours));
    }

    /** Hours since a usable dose, or NaN for a note that is not one (no units, no time, future). */
    private static double hoursSinceDose(Note note, LocalDateTime now) {
        if (note.getInsulin() == null || note.getInsulin() <= 0 || note.getTimestamp() == null) {
            return Double.NaN;
        }
        double hoursAgo = Duration.between(note.getTimestamp(), now).toMinutes() / 60.0;
        return hoursAgo < 0 ? Double.NaN : hoursAgo;
    }
}
