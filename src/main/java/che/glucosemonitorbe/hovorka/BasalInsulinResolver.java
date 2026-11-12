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
 * <h3>How much a dose suppresses</h3>
 * <p>A basal dose is titrated until fasting glucose holds steady, so the user's <em>usual</em> daily
 * amount is by construction the amount that gives full suppression - whether that is 0.15 or
 * 0.5 U/kg. Coverage is therefore measured against {@link #usualDailyUnits the user's own usual
 * daily basal}, read from the same log, and not against a population dose: a population reference
 * made every user below it drift upward in the forecast while their real glucose stayed flat.
 * Doses active at the same time add up (a twice-daily regimen is two half doses), capped at full
 * suppression. Less than usual - a reduced or overdue dose - is what lets EGP rise.</p>
 *
 * The peak suppression fraction {@link #PEAK_X3_BASAL} (0.40) is derived from the
 * steady-state identity EGP0 × (1 - x3) = F01 -> x3 = 1 - F01/EGP0 = 1 - 0.0097/0.0161 ≈ 0.40.
 */
@Component
public class BasalInsulinResolver {

    /** EGP suppression fraction at steady-state basal insulin (dimensionless, 0-1). */
    public static final double PEAK_X3_BASAL   = 0.40;

    /** Reference used when a caller's reference is unusable (not finite, or not positive). */
    public static final double DEFAULT_REFERENCE_UNITS = 20.0;

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
     * Estimates the current EGP suppression fraction x3_basal ∈ [0, 0.40], measuring the logged
     * doses against {@link #usualDailyUnits the user's own usual daily basal}.
     *
     * <p>Doses active at the same time add up, capped at the peak. Returns 0 if no dose is active;
     * callers must check
     * {@link #hasActiveDose} first, because "no active dose" means the log is silent, not that
     * the user has no basal insulin.</p>
     *
     * @param longActingNotes  notes with {@code isLongActing()==true} from recent history
     * @param now              current time
     * @param durationHours    duration of action of the user's long-acting insulin
     * @return                 x3_basal suppression fraction ∈ [0, PEAK_X3_BASAL]
     */
    public double resolveEgpSuppression(List<Note> longActingNotes, LocalDateTime now, double durationHours) {
        return resolveEgpSuppression(longActingNotes, now, durationHours,
                usualDailyUnits(longActingNotes, now));
    }

    /**
     * As {@link #resolveEgpSuppression(List, LocalDateTime, double)}, scaled by logged units.
     * {@code referenceUnits} is the amount that gives a full plateau - pass
     * {@link #usualDailyUnits}. Each dose contributes its units times its place on the curve; the
     * sum over the reference is the covered fraction, and more than the reference does not exceed
     * the peak. A non-finite or non-positive reference falls back to
     * {@link #DEFAULT_REFERENCE_UNITS}.
     */
    public double resolveEgpSuppression(List<Note> longActingNotes, LocalDateTime now,
                                        double durationHours, double referenceUnits) {
        if (longActingNotes == null) {
            return 0.0;
        }
        double reference = (Double.isFinite(referenceUnits) && referenceUnits > 0)
                ? referenceUnits : DEFAULT_REFERENCE_UNITS;
        double coveredUnits = 0.0;
        for (Note note : longActingNotes) {
            double hoursAgo = hoursSinceDose(note, now);
            if (Double.isNaN(hoursAgo)) continue;
            coveredUnits += note.getInsulin() * suppressionCurve(hoursAgo, durationHours) / PEAK_X3_BASAL;
        }
        return PEAK_X3_BASAL * Math.max(0.0, Math.min(1.0, coveredUnits / reference));
    }

    /** Doses logged less than this many hours after another belong to the same day's basal. */
    static final double SAME_DAY_HOURS = 18.0;

    /**
     * The user's usual daily basal [U], read from the log.
     *
     * <p>A group is a dose plus every dose logged less than {@link #SAME_DAY_HOURS} after it.
     * Doses are grouped by their spacing and not by 24-hour blocks counted back from {@code now},
     * so the answer does not change with the time of day it is asked. 18 hours sits between a
     * twice-daily gap (about 12 hours) and a once-daily gap (about 24).</p>
     *
     * <p>A single dose always counts: the largest one is a lower bound on a day's basal. A group
     * of several doses counts only as far as a second group reaches the same total, which is the
     * second-largest total among those groups. A twice-daily regimen repeats its pair of doses; a
     * late dose followed by an on-time one looks the same in the log but does not repeat. The
     * usual amount is the larger of the two: the largest single dose, and that second-largest
     * group total.</p>
     *
     * <p>Known limits: the first two doses of a twice-daily regimen read as a usual amount of one
     * dose until the pair repeats with the third dose, so coverage reads full in that stretch; and
     * two one-off late doses in the same log still read as a double day.</p>
     *
     * <p>Every usable dose in the list is counted, whatever its age; callers bound the list to
     * {@link #LOOKBACK_HOURS}.</p>
     *
     * <p>The largest single dose is used so that a day with a reduced dose is measured against a
     * normal day's dose and not against itself. 0 when the log holds no usable dose.</p>
     */
    public double usualDailyUnits(List<Note> longActingNotes, LocalDateTime now) {
        if (longActingNotes == null) {
            return 0.0;
        }
        double largestDose = 0.0;
        double largestGroup = 0.0;
        double secondLargestGroup = 0.0;
        for (Note first : longActingNotes) {
            double firstHoursAgo = hoursSinceDose(first, now);
            if (Double.isNaN(firstHoursAgo)) continue;
            largestDose = Math.max(largestDose, first.getInsulin());
            double total = 0.0;
            int doses = 0;
            for (Note other : longActingNotes) {
                double hoursAgo = hoursSinceDose(other, now);
                if (Double.isNaN(hoursAgo)) continue;
                double hoursAfterFirst = firstHoursAgo - hoursAgo;
                if (hoursAfterFirst >= 0 && hoursAfterFirst < SAME_DAY_HOURS) {
                    total += other.getInsulin();
                    doses++;
                }
            }
            if (doses < 2) continue;
            if (total > largestGroup) {
                secondLargestGroup = largestGroup;
                largestGroup = total;
            } else if (total > secondLargestGroup) {
                secondLargestGroup = total;
            }
        }
        return Math.max(largestDose, secondLargestGroup);
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
