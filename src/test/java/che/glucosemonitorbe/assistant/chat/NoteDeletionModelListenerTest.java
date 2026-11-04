package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import che.glucosemonitorbe.domain.NoteDeleted;
import che.glucosemonitorbe.domain.NoteLogged;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoteDeletionModelListenerTest {

    private final UUID user = UUID.randomUUID();
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private NoteDeletionModelListener listener;

    @BeforeEach
    void setUp() {
        when(messages.save(any())).thenAnswer(i -> i.getArgument(0));
        listener = new NoteDeletionModelListener(
                new ChatHistoryService(messages, JsonMapper.builder().findAndAddModules().build()));
    }

    @Test
    void aDeletedNoteIsWrittenAsAUserTurnTheModelWillSee() {
        listener.onNoteDeleted(new NoteDeleted(user, UUID.randomUUID(), null,
                "Deleted note at 23:10: meal Raffaello, carbs 17.4 g, text Raffaello 45 g."));

        var saved = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        ChatMessage told = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals(user, told.getUserId());
        assertEquals(ChatMessage.Role.USER, told.getRole());
        assertEquals(NoteDeletionModelListener.INTENT, told.getIntent());
        assertEquals("Deleted note at 23:10: meal Raffaello, carbs 17.4 g, text Raffaello 45 g.", told.getText());
    }

    @Test
    void anInsulinNoteAndACarbNoteAreBothWrittenIntoTheChat() {
        listener.onNoteLogged(new NoteLogged(user, UUID.randomUUID(), "Logged note at 23:10: insulin 2 U."));
        listener.onNoteLogged(new NoteLogged(user, UUID.randomUUID(), "Logged note at 23:12: carbs 40 g."));

        var saved = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        var texts = saved.getAllValues().stream().map(ChatMessage::getText).toList();
        assertEquals(true, texts.contains("Logged note at 23:10: insulin 2 U."));
        assertEquals(true, texts.contains("Logged note at 23:12: carbs 40 g."));
        assertEquals(NoteDeletionModelListener.LOGGED_INTENT, saved.getValue().getIntent());
    }

    @Test
    void aDeleteWithoutTextIsNotSent() {
        listener.onNoteDeleted(new NoteDeleted(user, UUID.randomUUID(), null));

        verify(messages, never()).save(any());
    }
}
