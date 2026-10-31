package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.domain.NoteDeleted;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuickLogDeletionListenerTest {

    private final UUID user = UUID.randomUUID();
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private QuickLogDeletionListener listener;

    @BeforeEach
    void setUp() {
        when(messages.save(any())).thenAnswer(i -> i.getArgument(0));
        listener = new QuickLogDeletionListener(
                new ChatHistoryService(messages, JsonMapper.builder().findAndAddModules().build()), notes);
    }

    private ChatMessage quickLog(UUID... noteIds) {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.USER);
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < noteIds.length; i++) ids.append(i == 0 ? "" : ",").append('"').append(noteIds[i]).append('"');
        m.setNoteIds(ids.append(']').toString());
        when(messages.findById(m.getId())).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void deletingTheLastNoteOfAQuickLogMarksItUndone() {
        UUID noteId = UUID.randomUUID();
        ChatMessage log = quickLog(noteId);

        listener.onNoteDeleted(new NoteDeleted(user, noteId, log.getId()));

        assertTrue(log.getPayload().contains("\"undone\":true"), log.getPayload());
    }

    @Test
    void aQuickLogWithANoteLeftStaysUndoable() {
        UUID deleted = UUID.randomUUID();
        UUID left = UUID.randomUUID();
        ChatMessage log = quickLog(deleted, left);
        when(notes.findByIdAndUserId(left, user)).thenReturn(Optional.of(mock(Note.class)));

        listener.onNoteDeleted(new NoteDeleted(user, deleted, log.getId()));

        assertNull(log.getPayload());
        verify(messages, never()).save(any());
    }

    @Test
    void anotherUsersMessageIsNotTouched() {
        UUID noteId = UUID.randomUUID();
        ChatMessage log = quickLog(noteId);

        listener.onNoteDeleted(new NoteDeleted(UUID.randomUUID(), noteId, log.getId()));

        verify(messages, never()).save(any());
    }
}
