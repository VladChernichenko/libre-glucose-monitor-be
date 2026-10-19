package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.GlycemicEventHistory;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/** One row of an event's timeline: detected -> resolved -> the user's answer. */
public record GlycemicEventHistoryDTO(
        String kind,
        @JsonFormat(pattern = GlycemicEventDTO.TIME) LocalDateTime at,
        String categoryBefore,
        String categoryAfter,
        Double confidence,
        String actor,
        String reason) {

    public static GlycemicEventHistoryDTO from(GlycemicEventHistory h) {
        return new GlycemicEventHistoryDTO(h.getKind().name(), h.getAt(),
                h.getCategoryBefore() == null ? null : h.getCategoryBefore().name(),
                h.getCategoryAfter() == null ? null : h.getCategoryAfter().name(),
                h.getConfidence(), h.getActor().name(), h.getReason());
    }
}
