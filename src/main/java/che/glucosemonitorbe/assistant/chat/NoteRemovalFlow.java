package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.exception.ResourceNotFoundException;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.NotesService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Remove the 1 U" from chat: finds the logged note the message names among the last {@link #LOOKBACK} of
 * notes and proposes it as a {@code removal} card; the note is deleted only when the user taps Remove
 * ({@code POST /api/assistant/removals/{messageId}/confirm}). State on the reply message:
 * {@code PROPOSED -> REMOVED}. Nothing is deleted from the message text alone.
 */
@Component
@RequiredArgsConstructor
public class NoteRemovalFlow {

    public static final String INTENT_REMOVE = "remove";
    public static final String INTENT_REMOVAL_PROPOSAL = "removal_proposal";
    public static final String INTENT_REMOVAL_NONE = "removal_none";
    public static final String PROPOSED = "PROPOSED";
    public static final String REMOVED = "REMOVED";
    static final Duration LOOKBACK = Duration.ofHours(24);
    /** Notes up to this far ahead of now still count (client clock skew, a just-logged note). */
    static final Duration AHEAD = Duration.ofMinutes(5);
    static final double INSULIN_TOLERANCE = 0.05;
    static final double CARBS_TOLERANCE = 0.5;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ChatHistoryService history;
    private final ChatMessageRepository messages;
    private final NoteRepository notes;
    private final NotesService notesService;

    /** Outcome of Remove: {@code removed} is false when the note was already gone. */
    public record Result(UUID messageId, String state, UUID noteId, boolean removed) {}

    /** @param now the assistant's now on the user's wall clock (notes are stored on it) */
    public void propose(UUID userId, ChatMessage userMessage, IntentClassifier.RemoveTarget target,
                        LocalDateTime now, String language, Consumer<AssistantEvent> sink) {
        long start = System.currentTimeMillis();
        boolean ru = "ru".equals(language);
        userMessage.setIntent(INTENT_REMOVE);
        history.save(userMessage);

        Optional<Note> found = match(userId, target == null ? IntentClassifier.RemoveTarget.NONE : target, now);
        ObjectNode payload = history.newPayload()
                .put("userMessageId", userMessage.getId().toString())
                .put("language", language);
        if (found.isEmpty()) {
            String text = ru ? "Не нашёл такую запись за последние 24 ч. Удалить её можно во вкладке «Заметки»."
                    : "I could not find that entry in the last 24 h. You can delete it in the Notes tab.";
            ChatMessage reply = history.appendAssistant(userMessage, text, INTENT_REMOVAL_NONE, payload, null,
                    System.currentTimeMillis() - start);
            sink.accept(new AssistantEvent.Token(text));
            sink.accept(new AssistantEvent.Done(userMessage.getId(), reply.getId(), INTENT_REMOVAL_NONE));
            return;
        }
        Note n = found.get();
        String at = n.getTimestamp().format(HH_MM);
        String label = label(n, ru);
        payload.put("state", PROPOSED).put("noteId", n.getId().toString())
                .put("at", at).put("label", label);
        if (positive(n.getInsulin())) payload.put("insulin", n.getInsulin());
        if (positive(n.getCarbs())) payload.put("carbs", n.getCarbs());
        String text = (ru ? "Удалить запись: " : "Remove this entry: ") + summary(at, n, label, ru)
                + (ru ? "? Нажмите «Удалить»." : "? Tap Remove.");
        ChatMessage reply = history.appendAssistant(userMessage, text, INTENT_REMOVAL_PROPOSAL, payload, null,
                System.currentTimeMillis() - start);
        sink.accept(new AssistantEvent.Removal(reply.getId(), n.getId(), at,
                positive(n.getInsulin()) ? n.getInsulin() : null, positive(n.getCarbs()) ? n.getCarbs() : null, label));
        sink.accept(new AssistantEvent.Done(userMessage.getId(), reply.getId(), INTENT_REMOVAL_PROPOSAL));
    }

    /**
     * Deletes the proposed note (owner only; 409 unless PROPOSED). The quick log that wrote it is marked
     * undone by {@link QuickLogDeletionListener} on the delete's {@code NoteDeleted} event.
     */
    @Transactional
    public Result confirm(UUID userId, UUID messageId) {
        ChatMessage m = messages.findForUpdate(messageId)
                .filter(x -> userId.equals(x.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Chat message not found: " + messageId));
        if (m.getRole() != ChatMessage.Role.ASSISTANT || !INTENT_REMOVAL_PROPOSAL.equals(m.getIntent())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This message does not support that action");
        }
        ObjectNode p = history.readPayload(m);
        if (!PROPOSED.equals(p.path("state").asText(""))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not allowed in state " + p.path("state").asText(""));
        }
        UUID noteId = UUID.fromString(p.path("noteId").asText());
        Optional<Note> note = notes.findByIdAndUserId(noteId, userId);
        boolean removed = note.isPresent() && notesService.deleteNote(userId, noteId);
        p.put("state", REMOVED);
        m.setPayload(p.toString());
        history.save(m);
        return new Result(m.getId(), REMOVED, noteId, removed);
    }

    /** The newest note in the window matching the named units / grams; with neither named, the newest note. */
    Optional<Note> match(UUID userId, IntentClassifier.RemoveTarget t, LocalDateTime now) {
        List<Note> window = notes.findByUserIdAndTimestampBetween(userId, now.minus(LOOKBACK), now.plus(AHEAD));
        if (window == null) return Optional.empty();
        return window.stream()
                .filter(n -> userId.equals(n.getUserId()) && n.getTimestamp() != null)
                .filter(n -> t.insulin() == null || close(n.getInsulin(), t.insulin(), INSULIN_TOLERANCE))
                .filter(n -> t.carbs() == null || close(n.getCarbs(), t.carbs(), CARBS_TOLERANCE))
                .max(Comparator.comparing(Note::getTimestamp));
    }

    static String label(Note n, boolean ru) {
        if (n.isLongActing()) return ru ? "длинный инсулин" : "long-acting insulin";
        if (n.isHypoTreatment()) return ru ? "быстрые углеводы" : "fast carbs";
        boolean insulin = positive(n.getInsulin());
        boolean carbs = positive(n.getCarbs());
        if (insulin && carbs) return ru ? "еда и инсулин" : "meal and insulin";
        if (insulin) return ru ? "инсулин" : "insulin";
        if (carbs) return ru ? "еда" : "meal";
        return ru ? "заметка" : "note";
    }

    private static String summary(String at, Note n, String label, boolean ru) {
        StringBuilder sb = new StringBuilder(at);
        if (positive(n.getInsulin())) sb.append(" · ").append(fmt(n.getInsulin())).append(ru ? " ЕД" : " U");
        if (positive(n.getCarbs())) sb.append(" · ").append(fmt(n.getCarbs())).append(ru ? " г" : " g");
        return sb.append(" · ").append(label).toString();
    }

    private static boolean close(Double v, double target, double tolerance) {
        return v != null && Math.abs(v - target) <= tolerance + 1e-9;
    }

    private static boolean positive(Double v) {
        return v != null && v > 0;
    }

    private static String fmt(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
