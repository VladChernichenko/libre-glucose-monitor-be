package che.glucosemonitorbe.domain;

import java.util.UUID;

/**
 * Published synchronously after a note is deleted, whatever deleted it (Notes tab, chat Undo, chat
 * Remove). A chat quick log whose notes are all gone must stop offering Undo and stop being shown as
 * the chat's latest line.
 *
 * @param chatMessageId the chat message that wrote the note, or null
 */
public record NoteDeleted(UUID userId, UUID noteId, UUID chatMessageId) {}
