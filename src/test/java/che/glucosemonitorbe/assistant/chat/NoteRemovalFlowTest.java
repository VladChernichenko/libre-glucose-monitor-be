package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.NotesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class NoteRemovalFlowTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 1, 58);

    private final UUID user = UUID.randomUUID();
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final NotesService notesService = mock(NotesService.class);
    private final Map<UUID, ChatMessage> stored = new HashMap<>();
    private final List<AssistantEvent> events = new ArrayList<>();
    private NoteRemovalFlow flow;

    @BeforeEach
    void setUp() {
        when(messages.save(any())).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            stored.put(m.getId(), m);
            return m;
        });
        when(messages.findById(any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.<UUID>getArgument(0))));
        when(messages.findForUpdate(any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.<UUID>getArgument(0))));
        flow = new NoteRemovalFlow(new ChatHistoryService(messages, objectMapper), messages, notes, notesService);
    }

    private Note note(LocalDateTime at, double insulin, double carbs) {
        Note n = new Note(user, at, carbs, insulin, "none");
        n.setId(UUID.randomUUID());
        n.setType(Note.TYPE_NORMAL);
        return n;
    }

    private ChatMessage userMessage(String text) {
        ChatMessage m = new ChatMessage();
        m.setUserId(user);
        m.setRole(ChatMessage.Role.USER);
        m.setText(text);
        return messages.save(m);
    }

    private AssistantEvent.Removal propose(IntentClassifier.RemoveTarget t) {
        flow.propose(user, userMessage("remove 1u"), t, NOW, "en", events::add);
        return events.stream().filter(AssistantEvent.Removal.class::isInstance)
                .map(AssistantEvent.Removal.class::cast).findFirst().orElse(null);
    }

    @Test
    void proposesTheNewestNoteWithTheNamedDoseAndDeletesNothing() {
        Note older = note(NOW.minusHours(3), 1.0, 0);
        Note meal = note(NOW.minusMinutes(20), 4.0, 40);
        Note latest = note(NOW.minusMinutes(2), 1.0, 0);
        when(notes.findByUserIdAndTimestampBetween(eq(user), any(), any())).thenReturn(List.of(older, meal, latest));

        AssistantEvent.Removal r = propose(new IntentClassifier.RemoveTarget(1.0, null));

        assertNotNull(r);
        assertEquals(latest.getId(), r.noteId());
        assertEquals("01:56", r.at());
        assertEquals(1.0, r.insulin());
        assertNull(r.carbs());
        assertEquals("insulin", r.label());
        verifyNoInteractions(notesService);
    }

    @Test
    void noMatchRepliesWithoutACard() {
        when(notes.findByUserIdAndTimestampBetween(eq(user), any(), any()))
                .thenReturn(List.of(note(NOW.minusMinutes(5), 4.0, 0)));

        assertNull(propose(new IntentClassifier.RemoveTarget(1.0, null)));
        assertTrue(events.stream().anyMatch(e -> e instanceof AssistantEvent.Done d
                && NoteRemovalFlow.INTENT_REMOVAL_NONE.equals(d.intent())));
    }

    @Test
    void confirmDeletesOnceAndMarksTheQuickLogUndone() {
        ChatMessage quickLog = userMessage("1u");
        Note n = note(NOW.minusMinutes(2), 1.0, 0);
        n.setChatMessageId(quickLog.getId());
        quickLog.setNoteIds("[\"" + n.getId() + "\"]");
        when(notes.findByUserIdAndTimestampBetween(eq(user), any(), any())).thenReturn(List.of(n));
        when(notes.findByIdAndUserId(n.getId(), user)).thenReturn(Optional.of(n));
        when(notesService.deleteNote(user, n.getId())).thenReturn(true);
        AssistantEvent.Removal r = propose(IntentClassifier.RemoveTarget.NONE);

        NoteRemovalFlow.Result result = flow.confirm(user, r.messageId());

        assertTrue(result.removed());
        assertEquals(NoteRemovalFlow.REMOVED, result.state());
        verify(notesService).deleteNote(user, n.getId());
        assertTrue(stored.get(quickLog.getId()).getPayload().contains("\"undone\":true"));
        assertThrows(ResponseStatusException.class, () -> flow.confirm(user, r.messageId()));
        verify(notesService, times(1)).deleteNote(any(), any());
    }

    @Test
    void anotherUserCannotConfirm() {
        Note n = note(NOW.minusMinutes(2), 1.0, 0);
        when(notes.findByUserIdAndTimestampBetween(eq(user), any(), any())).thenReturn(List.of(n));
        AssistantEvent.Removal r = propose(IntentClassifier.RemoveTarget.NONE);

        assertThrows(RuntimeException.class, () -> flow.confirm(UUID.randomUUID(), r.messageId()));
        verifyNoInteractions(notesService);
    }
}
