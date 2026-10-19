package che.glucosemonitorbe.empirical;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.entity.Note;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Maps the app's event model onto the empirical twin's event types (the table in
 * {@code specs/empirical-twin-port.md}).
 *
 * <p>App timestamps are the user's local wall time. The model only needs intervals between events
 * and the clock hour of a meal, so wall time is placed on a UTC clock by {@link #epochMs} and the
 * hour is read off the wall time itself.</p>
 */
public final class EmpiricalEventMapper {

    private EmpiricalEventMapper() {}

    /**
     * A carb entry: a rescue carb (hypo treatment) is fast sugar, anything else a meal. GI and the
     * fatty / fruit flags pass through; absent ones stay null, the model's neutral value.
     */
    public static EmpiricalEvent carbs(CarbsEntry entry) {
        EmpiricalEvent.Type type = RescueCarbProfile.isRescue(entry.getAbsorptionMode())
                ? EmpiricalEvent.Type.FAST : EmpiricalEvent.Type.MEAL;
        return new EmpiricalEvent(type, epochMs(entry.getTimestamp()), null,
                entry.getCarbs() != null ? entry.getCarbs() : 0.0, localHour(entry.getTimestamp()),
                entry.getFattyMeal(), entry.getFruit(), entry.getEstimatedGi());
    }

    /** A rapid-acting dose. */
    public static EmpiricalEvent insulin(InsulinDose dose) {
        return new EmpiricalEvent(EmpiricalEvent.Type.INSULIN, epochMs(dose.getTimestamp()), null,
                dose.getUnits() != null ? dose.getUnits() : 0.0, localHour(dose.getTimestamp()),
                null, null, null);
    }

    /**
     * An activity note with a duration, as a walk from its timestamp to timestamp + duration; empty
     * for any other note. The model has one activity term, fitted on walks, so every logged activity
     * maps to it whatever its type or intensity.
     */
    public static Optional<EmpiricalEvent> walk(Note note) {
        if (!note.isActivity() || note.getTimestamp() == null
                || note.getDurationMin() == null || note.getDurationMin() <= 0) {
            return Optional.empty();
        }
        long start = epochMs(note.getTimestamp());
        return Optional.of(new EmpiricalEvent(EmpiricalEvent.Type.WALK, start,
                start + note.getDurationMin() * 60_000L, 0.0, localHour(note.getTimestamp()),
                null, null, null));
    }

    /** Local wall time on a UTC clock. */
    public static long epochMs(LocalDateTime wallTime) {
        return wallTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static double localHour(LocalDateTime wallTime) {
        return wallTime.getHour() + wallTime.getMinute() / 60.0;
    }
}
