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
 * Long-acting insulin (Lantus/Tresiba) reaches steady-state plasma levels 3-6 h after
 * injection and maintains hepatic glucose production suppression throughout the day.
 * At therapeutic dosing, basal insulin suppresses roughly 40% of EGP, exactly balanced
 * by non-insulin-dependent glucose utilisation (F01) at fasting glucose.
 *
 * <p>At steady state:  EGP_net = EGP0 × (1 - x3_ss) = F01_abs</p>
 *
 * <h3>EGP suppression curve</h3>
 * <ul>
 *   <li>0-20 h after injection: full suppression maintained (plateau)</li>
 *   <li>20-28 h: waning (linear taper)</li>
 * </ul>
 * <p>There is deliberately no ramp-up phase: a freshly logged dose is assumed to
 * continue the steady-state coverage already established by the user's regular
 * dosing routine - the same assumption used when no long-acting note is logged
 * at all (see {@code egpNow = f01} fallback in
 * {@link HovorkaGlucosePredictionService}). Without this, logging today's dose
 * a few minutes ago would make the model <em>more</em> pessimistic about EGP
 * suppression than logging nothing at all, producing a spurious rising forecast
 * right after a routine injection.</p>
 * The peak suppression fraction {@link #PEAK_X3_BASAL} (0.40) is derived from the
 * steady-state identity EGP0 × (1 - x3) = F01 -> x3 = 1 - F01/EGP0 = 1 - 0.0097/0.0161 ≈ 0.40.
 */
@Component
public class BasalInsulinResolver {

    /** EGP suppression fraction at steady-state basal insulin (dimensionless, 0-1). */
    public static final double PEAK_X3_BASAL   = 0.40;

    /** Duration after which long-acting insulin has fully cleared [hours]. */
    public static final double BASAL_DIA_HOURS = 28.0;

    /** Hours after injection when waning begins. */
    private static final double WANE_START_HOURS = 20.0;

    /**
     * Estimates the current EGP suppression fraction x3_basal ∈ [0, 0.40].
     *
     * <p>Scans long-acting notes from the last {@link #BASAL_DIA_HOURS} hours.
     * If multiple overlapping doses are present, the maximum fraction is taken
     * (conservative - avoid double-counting). Returns 0 if no basal insulin is active.</p>
     *
     * @param longActingNotes  notes with {@code isLongActing()==true} from recent history
     * @param now              current time
     * @return                 x3_basal suppression fraction ∈ [0, PEAK_X3_BASAL]
     */
    public double resolveEgpSuppression(List<Note> longActingNotes, LocalDateTime now) {
        if (longActingNotes == null || longActingNotes.isEmpty()) {
            return 0.0;
        }
        double maxSuppression = 0.0;
        for (Note note : longActingNotes) {
            if (note.getInsulin() == null || note.getInsulin() <= 0) continue;
            if (note.getTimestamp() == null) continue;

            double hoursAgo = Duration.between(note.getTimestamp(), now).toMinutes() / 60.0;
            if (hoursAgo < 0 || hoursAgo > BASAL_DIA_HOURS) continue;

            double suppressionFraction = suppressionCurve(hoursAgo);
            maxSuppression = Math.max(maxSuppression, suppressionFraction);
        }
        return maxSuppression;
    }

    /**
     * EGP suppression profile as a function of hours since injection.
     *
     * <pre>
     *   0 -> wane_start : plateau at PEAK_X3_BASAL
     *   wane_start -> DIA : linear PEAK_X3_BASAL -> 0
     *   > DIA : 0
     * </pre>
     */
    double suppressionCurve(double hoursAgo) {
        if (hoursAgo < 0 || hoursAgo > BASAL_DIA_HOURS) return 0.0;
        if (hoursAgo <= WANE_START_HOURS) {
            return PEAK_X3_BASAL;
        }
        double waneRange = BASAL_DIA_HOURS - WANE_START_HOURS;
        double waneProgress = (hoursAgo - WANE_START_HOURS) / waneRange;
        return PEAK_X3_BASAL * (1.0 - waneProgress);
    }

    /**
     * Net EGP [mmol/min] accounting for basal insulin suppression.
     *
     * <p>At full basal coverage, egpNet = f01 (hepatic output = non-insulin-dependent
     * utilisation -> glucose is stable). When basal wanes, egpNet rises above f01,
     * causing fasting hyperglycaemia (dawn phenomenon).</p>
     *
     * @param f01Abs         F01 * weightKg [mmol/min]
     * @param egp0Abs        EGP0 * weightKg [mmol/min] (population: 0.0161 * weight)
     * @param x3Basal        suppression fraction from {@link #resolveEgpSuppression}
     */
    public double netEgp(double f01Abs, double egp0Abs, double x3Basal) {
        // When x3 = PEAK_X3_BASAL ≈ 0.40: EGP_net ≈ EGP0 * 0.60 ≈ 0.0097*W = f01
        // The net contribution to Q1 is: egpNet - f01  (= 0 at steady state)
        // At x3=0 (no basal): egpNet = EGP0 -> net positive -> glucose rises
        return egp0Abs * (1.0 - x3Basal);
    }
}
