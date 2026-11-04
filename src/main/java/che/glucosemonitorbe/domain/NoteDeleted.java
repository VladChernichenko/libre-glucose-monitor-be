package che.glucosemonitorbe.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published synchronously after a note is deleted, whatever deleted it (Notes tab, chat Undo, chat
 * Remove). A chat quick log whose notes are all gone must stop offering Undo and stop being shown as
 * the chat's latest line. {@code text} is the sentence the chat model is told, describing this note.
 * {@code timestamp} is the note's wall time, so the meal RAG can drop it and rebuild the meals that
 * included it.
 *
 * @param chatMessageId the chat message that wrote the note, or null
 * @param text what was deleted, or null when the caller has nothing to tell the model
 * @param timestamp the note's wall time, or null
 */
public record NoteDeleted(UUID userId, UUID noteId, UUID chatMessageId, String text, LocalDateTime timestamp) {

    public NoteDeleted(UUID userId, UUID noteId, UUID chatMessageId) {
        this(userId, noteId, chatMessageId, null, null);
    }

    public NoteDeleted(UUID userId, UUID noteId, UUID chatMessageId, String text) {
        this(userId, noteId, chatMessageId, text, null);
    }
}
