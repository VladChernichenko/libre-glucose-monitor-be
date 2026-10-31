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
 * chat Undo, chat Remove), so the chat stops offering Undo and stops showing it as the latest line.
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
                    .filter(m -> e.userId().equals(m.getUserId()) && m.getRole() == ChatMessage.Role.USER)
                    .filter(m -> history.readNoteIds(m).contains(e.noteId()))
                    .filter(m -> history.readNoteIds(m).stream().noneMatch(id -> stillThere(e.userId(), id)))
                    .ifPresent(m -> {
                        ObjectNode p = history.readPayload(m);
                        if (p.path("undone").asBoolean(false)) return;
                        p.put("undone", true);
                        m.setPayload(p.toString());
                        history.save(m);
                    });
        } catch (RuntimeException ex) {
            log.warn("Quick log not marked undone after note {} was deleted: {}", e.noteId(), ex.toString());
        }
    }

    private boolean stillThere(UUID userId, UUID noteId) {
        return notes.findByIdAndUserId(noteId, userId).isPresent();
    }
}
