package che.glucosemonitorbe.domain;

import java.util.UUID;

/**
 * A note saved outside the chat (Notes tab, a confirmation that did not start from a message).
 * {@code text} is the sentence the chat shows and the model counts: the insulin, the carbs, or both.
 *
 * @param text the entry, in the same shape as a deletion sentence
 */
public record NoteLogged(UUID userId, UUID noteId, String text) {}
