package che.glucosemonitorbe.service.prebolus;

import java.time.LocalDateTime;

/**
 * A pre-bolus that is in flight: insulin has been logged and the meal has not yet
 * been recorded.
 *
 * @param bolusTime      when the dose was administered
 * @param units          units from the logged note - the source of truth
 * @param elapsedMinutes minutes from {@code bolusTime} to the prediction anchor
 * @param source         how the context was established
 */
public record PreBolusContext(
        LocalDateTime bolusTime,
        double units,
        int elapsedMinutes,
        Source source) {

    public enum Source {
        /** Client supplied {@code insulinLoggedAt} and it matched a stored note. */
        EXPLICIT,
        /** Inferred from a {@code pre-bolus} note with no meal after it. */
        DETECTED
    }
}
