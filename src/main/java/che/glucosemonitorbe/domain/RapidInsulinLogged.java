package che.glucosemonitorbe.domain;

import che.glucosemonitorbe.entity.Note;

import java.util.UUID;

/**
 * Published synchronously, inside the writer's transaction, when a rapid-insulin note (insulin &gt; 0,
 * not long-acting) is created. A PLANNED meal plan priced before this dose is no longer safe to Took.
 *
 * @param chatMessageId the chat message that wrote the note (a meal plan's own Took dose carries the
 *                      plan's id), or null
 */
public record RapidInsulinLogged(UUID userId, UUID noteId, UUID chatMessageId) {

    public static boolean isRapidDose(Note note) {
        return note.getInsulin() != null && note.getInsulin() > 0 && !note.isLongActing();
    }
}
