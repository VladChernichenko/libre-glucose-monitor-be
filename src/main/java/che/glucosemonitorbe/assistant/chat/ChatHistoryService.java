package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.dto.AiAnalysisRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Reads and writes {@code chat_message} rows: history pages, LLM turns, and per-message JSON fields. */
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    public static final int DEFAULT_PAGE = 50;
    public static final int MAX_PAGE = 100;
    /** Cursor slack so a message written in the same instant as the request is still returned. */
    private static final Duration DEFAULT_CURSOR_SLACK = Duration.ofMinutes(1);

    private final ChatMessageRepository repository;
    private final ObjectMapper objectMapper;

    /** A page of the user's history strictly older than {@code before} (default: now), newest first. */
    public List<ChatMessageDTO> history(UUID userId, Instant before, Integer limit) {
        Instant cursor = before != null ? before : Instant.now().plus(DEFAULT_CURSOR_SLACK);
        int size = limit == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, limit));
        return repository.findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(userId, cursor, PageRequest.of(0, size))
                .stream().map(this::toDto).toList();
    }

    /** The last {@code n} turns before {@code before}, oldest first, as LLM conversation turns. */
    public List<AiAnalysisRequest.AiChatTurnDto> recentTurns(UUID userId, Instant before, int n) {
        List<ChatMessage> newestFirst = repository.findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                userId, before, PageRequest.of(0, n));
        List<AiAnalysisRequest.AiChatTurnDto> turns = new ArrayList<>();
        for (ChatMessage m : newestFirst) {
            if (m.getText() == null || m.getText().isBlank()) continue;
            AiAnalysisRequest.AiChatTurnDto t = new AiAnalysisRequest.AiChatTurnDto();
            t.setRole(m.getRole() == ChatMessage.Role.ASSISTANT ? "assistant" : "user");
            t.setContent(m.getText());
            turns.add(t);
        }
        Collections.reverse(turns);
        return turns;
    }

    public ChatMessage appendUser(UUID userId, String text, String photoKey, Instant at) {
        ChatMessage m = new ChatMessage();
        m.setUserId(userId);
        m.setRole(ChatMessage.Role.USER);
        m.setText(text);
        m.setPhotoKey(photoKey);
        m.setCreatedAt(at);
        return repository.save(m);
    }

    /** Saves an assistant reply, always ordered strictly after the user message it answers. */
    public ChatMessage appendAssistant(ChatMessage userMessage, String text, String intent, ObjectNode payload,
                                       String modelId, Long latencyMs) {
        Instant now = Instant.now();
        Instant floor = userMessage.getCreatedAt().plusMillis(1);
        ChatMessage m = new ChatMessage();
        m.setUserId(userMessage.getUserId());
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setCreatedAt(now.isAfter(floor) ? now : floor);
        m.setText(text);
        m.setIntent(intent);
        m.setPayload(payload == null ? null : payload.toString());
        m.setModelId(modelId);
        m.setLatencyMs(latencyMs);
        return repository.save(m);
    }

    /** Fills in a streamed reply's text after the fact; leaves the payload alone. */
    public void updateReplyText(UUID id, String text, String modelId, Long latencyMs) {
        repository.updateReplyText(id, text, modelId, latencyMs);
    }

    public ChatMessage save(ChatMessage m) {
        return repository.save(m);
    }

    public Optional<ChatMessage> find(UUID id) {
        return repository.findById(id);
    }

    public String writeNoteIds(List<UUID> noteIds) {
        try {
            return objectMapper.writeValueAsString(noteIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<UUID> readNoteIds(ChatMessage m) {
        if (m.getNoteIds() == null || m.getNoteIds().isBlank()) return List.of();
        try {
            return objectMapper.readValue(m.getNoteIds(), new TypeReference<List<UUID>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt note_ids on chat message " + m.getId(), e);
        }
    }

    /** The message's payload as a mutable object (empty when absent or not an object). */
    public ObjectNode readPayload(ChatMessage m) {
        if (m.getPayload() == null || m.getPayload().isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode node = objectMapper.readTree(m.getPayload());
            return node instanceof ObjectNode o ? o : objectMapper.createObjectNode();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt payload on chat message " + m.getId(), e);
        }
    }

    public ObjectNode newPayload() {
        return objectMapper.createObjectNode();
    }

    private ChatMessageDTO toDto(ChatMessage m) {
        JsonNode payload = m.getPayload() == null ? null : readPayload(m);
        return new ChatMessageDTO(m.getId(), m.getCreatedAt(), m.getRole(), m.getText(), m.getIntent(),
                m.getPhotoKey() != null, readNoteIds(m), payload);
    }
}
