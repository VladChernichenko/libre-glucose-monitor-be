package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.domain.NoteDeleted;
import che.glucosemonitorbe.repository.NoteRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Marks a chat quick log undone once every note it wrote is gone, however they were deleted (Notes tab,
 * chat Undo, chat Remove), so the chat stops offering Undo and stops showing it as the latest line. A meal
 * plan card whose logged dose and meal are all gone is marked {@code notesDeleted} the same way.
 * Failures are logged and swallowed: the delete itself has already happened.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickLogDeletionListener {

    private final ChatHistoryService history;
    private final NoteRepository notes;

    @EventListener
    public void onNoteDeleted(NoteDeleted e) {
        if (e.chatMessageId() == null) return;
        try {
            history.find(e.chatMessageId())
                    .filter(m -> e.userId().equals(m.getUserId()))
                    .filter(m -> flagFor(m) != null)
                    .filter(m -> history.readNoteIds(m).contains(e.noteId()))
                    .filter(m -> history.readNoteIds(m).stream().noneMatch(id -> stillThere(e.userId(), id)))
                    .ifPresent(m -> {
                        String flag = flagFor(m);
                        ObjectNode p = history.readPayload(m);
                        if (p.path(flag).asBoolean(false)) return;
                        p.put(flag, true);
                        m.setPayload(p.toString());
                        history.save(m);
                    });
        } catch (RuntimeException ex) {
            log.warn("Quick log not marked undone after note {} was deleted: {}", e.noteId(), ex.toString());
        }
    }

    /** A quick log becomes {@code undone}; a meal plan card {@code notesDeleted} (its text is struck through). */
    private static String flagFor(ChatMessage m) {
        if (m.getRole() == ChatMessage.Role.USER) return "undone";
        if (m.getRole() == ChatMessage.Role.ASSISTANT
                && che.glucosemonitorbe.assistant.mealplan.MealActionService.INTENT_MEAL_PLAN.equals(m.getIntent())) {
            return "notesDeleted";
        }
        return null;
    }

    private boolean stillThere(UUID userId, UUID noteId) {
        return notes.findByIdAndUserId(noteId, userId).isPresent();
    }
}
