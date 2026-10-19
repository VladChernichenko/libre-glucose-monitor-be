package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.glycemic.EventPresentation;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** API view of a {@link GlycemicEvent}. Times are the user's local wall time; glucose is mmol/L. */
public record GlycemicEventDTO(
        UUID id,
        String category,
        String detectorCategory,
        String userLabel,
        String displayLabel,
        String direction,
        String phase,
        String state,
        @JsonFormat(pattern = TIME) LocalDateTime onsetAt,
        @JsonFormat(pattern = TIME) LocalDateTime windowStart,
        @JsonFormat(pattern = TIME) LocalDateTime windowEnd,
        @JsonFormat(pattern = TIME) LocalDateTime resolvesAt,
        @JsonFormat(pattern = TIME) LocalDateTime detectedAt,
        @JsonFormat(pattern = TIME) LocalDateTime resolvedAt,
        Double confidence,
        boolean suppressed,
        UUID linkedEventId,
        Double meanResidualMmol,
        Double sigmaMultiple,
        List<String> promptOptions) {

    /** The note-DTO convention the iOS decoder parses; LocalDateTime.now() would otherwise emit nanoseconds. */
    public static final String TIME = "yyyy-MM-dd'T'HH:mm:ss";

    public static GlycemicEventDTO from(GlycemicEvent e) {
        return new GlycemicEventDTO(e.getId(), e.getCategory().name(),
                e.getDetectorCategory() == null ? null : e.getDetectorCategory().name(),
                e.getUserLabel() == null ? null : e.getUserLabel().name(),
                EventPresentation.displayLabel(e), e.getDirection().name(), e.getPhase().name(), e.getState().name(),
                e.getOnsetAt(), e.getWindowStart(), e.getWindowEnd(), e.getResolvesAt(), e.getDetectedAt(),
                e.getResolvedAt(), e.getConfidence(), e.isSuppressed(), e.getLinkedEventId(),
                e.getMeanResidualMmol(), e.getSigmaMultiple(),
                EventPresentation.promptOptions(e).stream().map(Enum::name).toList());
    }
}
