package che.glucosemonitorbe.assistant.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One chat turn as returned by {@code GET /api/assistant/messages}. */
public record ChatMessageDTO(
        UUID id,
        Instant createdAt,
        ChatMessage.Role role,
        String text,
        String intent,
        boolean hasPhoto,
        List<UUID> noteIds,
        JsonNode payload) {}
