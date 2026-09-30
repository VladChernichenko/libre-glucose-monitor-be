package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.GlycemicEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/** API view of an {@link GlycemicEvent}. */
public record GlycemicEventDTO(
        UUID id,
        String category,
        String direction,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        Double meanResidualMmol,
        Double sigmaMultiple,
        String state,
        LocalDateTime detectedAt,
        LocalDateTime resolvedAt) {

    public static GlycemicEventDTO from(GlycemicEvent f) {
        return new GlycemicEventDTO(
                f.getId(),
                f.getCategory().name(),
                f.getDirection().name(),
                f.getWindowStart(),
                f.getWindowEnd(),
                f.getMeanResidualMmol(),
                f.getSigmaMultiple(),
                f.getState().name(),
                f.getDetectedAt(),
                f.getResolvedAt());
    }
}
