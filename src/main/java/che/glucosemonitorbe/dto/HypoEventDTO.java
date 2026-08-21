package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.HypoEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/** API view of a {@link HypoEvent}. Glucose is mmol/L; the client converts for display. */
public record HypoEventDTO(
        UUID id,
        Double triggerGlucoseMmol,
        String state,
        UUID noteId,
        LocalDateTime detectedAt,
        LocalDateTime resolvedAt) {

    public static HypoEventDTO from(HypoEvent e) {
        return new HypoEventDTO(
                e.getId(),
                e.getTriggerGlucoseMmol(),
                e.getState().name(),
                e.getNoteId(),
                e.getDetectedAt(),
                e.getResolvedAt());
    }
}
