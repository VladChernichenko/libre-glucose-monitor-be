package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.domain.NoteDeleted;
import che.glucosemonitorbe.domain.NoteLogged;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes a saved or deleted note into the chat, insulin and carbs alike, so the next model call
 * sees the entry. The row is a user turn with intent {@code note_logged} or {@code note_deleted}.
 * Failures are logged and swallowed: the note change has already happened.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoteDeletionModelListener {

    static final String INTENT = "note_deleted";
    static final String LOGGED_INTENT = "note_logged";

    private final ChatHistoryService history;

    @EventListener
    public void onNoteDeleted(NoteDeleted e) {
        write(e.userId(), e.noteId(), e.text(), INTENT, "Deleted");
    }

    @EventListener
    public void onNoteLogged(NoteLogged e) {
        write(e.userId(), e.noteId(), e.text(), LOGGED_INTENT, "Logged");
    }

    private void write(UUID userId, UUID noteId, String text, String intent, String what) {
        if (text == null || text.isBlank() || userId == null) return;
        try {
            ChatMessage m = history.appendUser(userId, text.trim(), null, Instant.now());
            m.setIntent(intent);
            history.save(m);
        } catch (RuntimeException ex) {
            log.warn("{} note {} was not written into the chat: {}", what, noteId, ex.toString());
        }
    }
}
