package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.domain.NoteDeleted;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes the deleted note's sentence into the chat, so the next model call sees which entry is gone.
 * The row is a user turn (the user did the delete) with intent {@code note_deleted}. Failures are
 * logged and swallowed: the note is already deleted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoteDeletionModelListener {

    static final String INTENT = "note_deleted";

    private final ChatHistoryService history;

    @EventListener
    public void onNoteDeleted(NoteDeleted e) {
        if (e.text() == null || e.text().isBlank()) return;
        try {
            ChatMessage m = history.appendUser(e.userId(), e.text().trim(), null, Instant.now());
            m.setIntent(INTENT);
            history.save(m);
        } catch (RuntimeException ex) {
            log.warn("Deleted note {} was not told to the model: {}", e.noteId(), ex.toString());
        }
    }
}
