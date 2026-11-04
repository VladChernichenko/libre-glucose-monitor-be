package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.domain.NoteDeleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the meal RAG aligned with notes: a deleted note is removed at once, and meals that included
 * it are rebuilt from what remains. Failures are logged and swallowed; the note is already deleted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MealEpisodeSyncListener {

    private final MealEpisodeScheduler scheduler;

    @EventListener
    public void onNoteDeleted(NoteDeleted e) {
        try {
            scheduler.syncDeletedNote(e.userId(), e.noteId(), e.timestamp());
        } catch (RuntimeException ex) {
            log.warn("Meal RAG not updated after note {} was deleted: {}", e.noteId(), ex.toString());
        }
    }
}
