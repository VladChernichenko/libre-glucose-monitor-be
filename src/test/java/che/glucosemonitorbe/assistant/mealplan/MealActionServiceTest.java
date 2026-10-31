package che.glucosemonitorbe.assistant.mealplan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.assistant.chat.ChatHistoryService;
import che.glucosemonitorbe.assistant.chat.ChatMessage;
import che.glucosemonitorbe.assistant.chat.ChatMessageRepository;
import che.glucosemonitorbe.assistant.quicklog.AssistantNoteWriter;
import che.glucosemonitorbe.assistant.quicklog.QuickLogCommand;
import che.glucosemonitorbe.assistant.quicklog.QuickLogRejectedException;
import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.exception.ResourceNotFoundException;
import che.glucosemonitorbe.service.NotesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MealActionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 12, 0);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final AssistantNoteWriter noteWriter = mock(AssistantNoteWriter.class);
    private final NotesService notesService = mock(NotesService.class);
    private final UUID user = UUID.randomUUID();
    private MealActionService service;

    @BeforeEach
    void setUp() {
        when(repository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service = new MealActionService(repository, new ChatHistoryService(repository, objectMapper),
                noteWriter, notesService, objectMapper);
    }

    private ChatMessage planMessage() {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setIntent(MealActionService.INTENT_MEAL_PLAN);
        ObjectNode payload = objectMapper.createObjectNode().put("state", MealActionService.PLANNED)
                .put("description", "chicken soup, rye bread 40 g");
        payload.set("meal", objectMapper.valueToTree(new MealMacros(45.0, 12.0, 30.0, 4.0, 50.0)));
        m.setPayload(payload.toString());
        when(repository.findForUpdate(m.getId())).thenReturn(Optional.of(m));
        return m;
    }

    private UUID stubDoseNote() {
        UUID noteId = UUID.randomUUID();
        when(noteWriter.write(eq(user), any(QuickLogCommand.class), any()))
                .thenReturn(new AssistantNoteWriter.WriteResult(List.of(noteId), null));
        return noteId;
    }

    private UUID stubMealNote() {
        UUID noteId = UUID.randomUUID();
        NoteDto dto = new NoteDto();
        dto.setId(noteId);
        when(notesService.createNote(eq(user), any(CreateNoteRequest.class))).thenReturn(dto);
        return noteId;
    }

    @Test
    void tookSavesDoseOnlyNoMealNote() throws Exception {
        ChatMessage m = planMessage();
        UUID doseNote = stubDoseNote();

        MealActionService.ActionResult r = service.took(user, m.getId(), 4.5, 15, NOW);

        ArgumentCaptor<QuickLogCommand> cmd = ArgumentCaptor.forClass(QuickLogCommand.class);
        verify(noteWriter).write(eq(user), cmd.capture(), eq(m.getId()));
        assertEquals(QuickLogCommand.Kind.RAPID, cmd.getValue().kind());
        assertEquals(4.5, cmd.getValue().units());
        assertNull(cmd.getValue().carbs(), "the dose note carries no carbs: COB is unchanged");
        assertEquals(NOW, cmd.getValue().at());
        verify(notesService, never()).createNote(any(), any());

        assertEquals(MealActionService.TOOK, r.state());
        assertEquals(doseNote, r.noteId());
        JsonNode p = objectMapper.readTree(m.getPayload().toString());
        assertEquals("TOOK", p.get("state").asText());
        assertEquals(NOW.toString(), p.get("tookAt").asText());
        assertEquals(15, p.get("pauseMinutes").asInt());
        assertEquals(4.5, p.get("doseUnits").asDouble());
        assertEquals(doseNote.toString(), p.get("doseNoteId").asText());
        assertEquals(NOW.plusMinutes(15).toString(), p.get("eatAt").asText());
        assertEquals(NOW.plusMinutes(60).toString(), p.get("overdueAt").asText());
        assertEquals(45.0, p.get("meal").get("carbs").asDouble(), "planned meal is kept on the message");
    }

    @Test
    void eatingSavesMealNoteAtTapTime() throws Exception {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);
        UUID mealNote = stubMealNote();

        LocalDateTime tap = NOW.plusMinutes(22);
        MealActionService.ActionResult r = service.eating(user, m.getId(),
                new MealMacros(50.0, 10.0, 28.0, 5.0, null), tap);

        ArgumentCaptor<CreateNoteRequest> req = ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(notesService).createNote(eq(user), req.capture());
        assertEquals(tap, req.getValue().getTimestamp());
        assertEquals(50.0, req.getValue().getCarbs());
        assertEquals(0.0, req.getValue().getInsulin());
        assertEquals(m.getId(), req.getValue().getChatMessageId());
        JsonNode profile = objectMapper.readTree(req.getValue().getNutritionProfile());
        assertEquals(10.0, profile.get("fat").asDouble());
        assertEquals(28.0, profile.get("protein").asDouble());
        assertEquals(5.0, profile.get("fiber").asDouble());

        assertEquals(MealActionService.EATEN, r.state());
        assertEquals(mealNote, r.noteId());
        JsonNode p = objectMapper.readTree(m.getPayload());
        assertEquals("EATEN", p.get("state").asText());
        assertEquals(tap.toString(), p.get("eatenAt").asText());
        assertEquals(mealNote.toString(), p.get("mealNoteId").asText());
    }

    @Test
    void eatingWithoutMacrosUsesThePlannedMeal() {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);
        stubMealNote();

        service.eating(user, m.getId(), null, NOW.plusMinutes(15));

        ArgumentCaptor<CreateNoteRequest> req = ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(notesService).createNote(eq(user), req.capture());
        assertEquals(45.0, req.getValue().getCarbs());
    }

    @Test
    void eatingBeforeTookIsConflict() {
        ChatMessage m = planMessage();
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.eating(user, m.getId(), null, NOW));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(notesService, never()).createNote(any(), any());
    }

    @Test
    void overdueAfterPausePlus45() throws Exception {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);

        assertFalse(service.overdue(m, NOW.plusMinutes(60)), "exactly pause + 45 is not yet overdue");
        assertTrue(service.overdue(m, NOW.plusMinutes(61)));

        stubMealNote();
        service.eating(user, m.getId(), null, NOW.plusMinutes(70));
        assertFalse(service.overdue(m, NOW.plusMinutes(90)), "an eaten meal is never overdue");
    }

    @Test
    void plannedIsNeverOverdue() {
        ChatMessage m = planMessage();
        assertFalse(service.overdue(m, NOW.plusHours(5)));
    }

    @Test
    void doubleTookRejected() {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.took(user, m.getId(), 4.5, 15, NOW.plusMinutes(1)));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(noteWriter, times(1)).write(any(), any(), any());
    }

    @Test
    void tookOnAnotherUsersPlanIsNotFound() {
        ChatMessage m = planMessage();
        assertThrows(ResourceNotFoundException.class,
                () -> service.took(UUID.randomUUID(), m.getId(), 4.5, 15, NOW));
        verifyNoInteractions(noteWriter);
    }

    @Test
    void tookRejectsBadDoseAndPause() {
        ChatMessage m = planMessage();
        for (double dose : new double[] {0.0, -1.0, Double.NaN}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.took(user, m.getId(), dose, 15, NOW));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }
        for (int pause : new int[] {-1, 61}) {
            assertThrows(ResponseStatusException.class, () -> service.took(user, m.getId(), 4.0, pause, NOW));
        }
        verifyNoInteractions(noteWriter);
    }

    @Test
    void tookAboveMaxBolusIsBadRequestAndStateUnchanged() throws Exception {
        ChatMessage m = planMessage();
        when(noteWriter.write(any(), any(), any())).thenThrow(new QuickLogRejectedException("above max"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.took(user, m.getId(), 40.0, 15, NOW));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("PLANNED", objectMapper.readTree(m.getPayload()).get("state").asText());
    }

    @Test
    void eatingRejectsInvalidMacros() {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);
        assertThrows(ResponseStatusException.class, () -> service.eating(user, m.getId(),
                new MealMacros(-5.0, 0, 0, 0, null), NOW.plusMinutes(15)));
        assertThrows(ResponseStatusException.class, () -> service.eating(user, m.getId(),
                new MealMacros(301.0, 0, 0, 0, null), NOW.plusMinutes(15)));
        assertThrows(ResponseStatusException.class, () -> service.eating(user, m.getId(),
                new MealMacros(0, 0, 0, 0, null), NOW.plusMinutes(15)));
        verify(notesService, never()).createNote(any(), any());
    }

    @Test
    void skipAfterTookKeepsTheDoseAndEndsThePlan() throws Exception {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);

        MealActionService.ActionResult r = service.skip(user, m.getId(), NOW.plusMinutes(5));
        assertEquals(MealActionService.SKIPPED, r.state());
        verify(noteWriter, never()).undo(any(), any());
        assertThrows(ResponseStatusException.class, () -> service.eating(user, m.getId(), null, NOW.plusMinutes(6)));
        assertFalse(service.overdue(m, NOW.plusHours(3)));
        assertEquals(NOW.plusMinutes(5).toString(), objectMapper.readTree(m.getPayload()).get("skippedAt").asText());
    }

    @Test
    void confirmEventSavesTaggedNoteOnce() throws Exception {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setIntent(MealActionService.INTENT_EVENT_PROPOSAL);
        m.setPayload("{\"state\":\"PROPOSED\",\"tags\":[\"HOT_SHOWER\"],\"comment\":\"hot shower\"}");
        when(repository.findForUpdate(m.getId())).thenReturn(Optional.of(m));
        UUID noteId = stubMealNote();

        MealActionService.ActionResult r = service.confirmEvent(user, m.getId(), NOW);

        ArgumentCaptor<CreateNoteRequest> req = ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(notesService).createNote(eq(user), req.capture());
        assertEquals(NOW, req.getValue().getTimestamp());
        assertEquals(0.0, req.getValue().getCarbs());
        assertEquals("[\"HOT_SHOWER\"]", req.getValue().getContextTags());
        assertEquals("hot shower", req.getValue().getComment());
        assertEquals(noteId, r.noteId());
        assertEquals(MealActionService.CONFIRMED, r.state());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.confirmEvent(user, m.getId(), NOW));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void tookOnAnEventProposalIsConflict() {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setIntent(MealActionService.INTENT_EVENT_PROPOSAL);
        m.setPayload("{\"state\":\"PROPOSED\"}");
        when(repository.findForUpdate(m.getId())).thenReturn(Optional.of(m));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.took(user, m.getId(), 3.0, 10, NOW));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }
}
