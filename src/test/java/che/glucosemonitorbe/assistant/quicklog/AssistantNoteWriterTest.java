package che.glucosemonitorbe.assistant.quicklog;

import static che.glucosemonitorbe.assistant.quicklog.QuickLogCommand.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.NotesService;
import che.glucosemonitorbe.service.UserSettingsService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssistantNoteWriterTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 30, 12, 0);

    private final NotesService notes = mock(NotesService.class);
    private final HypoEventService hypo = mock(HypoEventService.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final InsulinCalculatorService calc = mock(InsulinCalculatorService.class);
    private final AssistantNoteWriter writer = new AssistantNoteWriter(notes, hypo, settings, calc);

    private final UUID user = UUID.randomUUID();
    private final UUID msg = UUID.randomUUID();
    private final UserSettingsDTO userSettings = new UserSettingsDTO();

    @BeforeEach
    void setUp() {
        when(settings.getUserSettings(user)).thenReturn(userSettings);
        when(calc.maxBolusUnits(userSettings)).thenReturn(21.0);
        when(hypo.findOpenEvent(user)).thenReturn(Optional.empty());
        when(notes.createNote(eq(user), any())).thenAnswer(i -> {
            NoteDto dto = new NoteDto();
            dto.setId(UUID.randomUUID());
            return dto;
        });
    }

    private CreateNoteRequest captureRequest() {
        ArgumentCaptor<CreateNoteRequest> c = ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(notes).createNote(eq(user), c.capture());
        return c.getValue();
    }

    @Test
    void fastSugarAttachesToOpenHypoEvent() {
        UUID eventId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        when(hypo.findOpenEvent(user)).thenReturn(Optional.of(
                new HypoEventDTO(eventId, 3.2, "OPEN", null, AT, null)));
        when(hypo.confirm(user, eventId, 15.0, msg)).thenReturn(
                new HypoEventDTO(eventId, 3.2, "CONFIRMED", noteId, AT, AT));

        var r = writer.write(user, new QuickLogCommand(FAST_SUGAR, null, 15.0, AT), msg);

        assertEquals(List.of(noteId), r.noteIds());
        assertEquals(eventId, r.hypoEventId());
        verify(notes, never()).createNote(any(), any());
    }

    @Test
    void fastSugarWithoutOpenEventCreatesHypoTreatmentNote() {
        var r = writer.write(user, new QuickLogCommand(FAST_SUGAR, null, 15.0, AT), msg);

        CreateNoteRequest req = captureRequest();
        assertEquals(Note.TYPE_HYPO_TREATMENT, req.getType());
        assertEquals(15.0, req.getCarbs());
        assertEquals(0.0, req.getInsulin());
        assertEquals(AT, req.getTimestamp());
        assertEquals(msg, req.getChatMessageId());
        assertNull(r.hypoEventId());
        assertEquals(1, r.noteIds().size());
        verify(hypo, never()).confirm(any(), any(), any(), any());
    }

    @Test
    void rapidWithMealCreatesOneNoteWithBoth() {
        var r = writer.write(user, new QuickLogCommand(RAPID_WITH_MEAL, 5.0, 50.0, AT), msg);

        CreateNoteRequest req = captureRequest();
        assertEquals(5.0, req.getInsulin());
        assertEquals(50.0, req.getCarbs());
        assertEquals(Note.TYPE_NORMAL, req.getType());
        assertEquals(msg, req.getChatMessageId());
        assertEquals(1, r.noteIds().size());
    }

    @Test
    void longActingUsesTypeLongActing() {
        writer.write(user, new QuickLogCommand(LONG_ACTING, 20.0, null, AT), msg);

        CreateNoteRequest req = captureRequest();
        assertEquals(Note.TYPE_LONG_ACTING, req.getType());
        assertEquals(20.0, req.getInsulin());
        assertEquals(0.0, req.getCarbs());
    }

    @Test
    void doseAboveMaxBolusRejected() {
        var ex = assertThrows(QuickLogRejectedException.class,
                () -> writer.write(user, new QuickLogCommand(RAPID, 22.0, null, AT), msg));
        assertNotNull(ex.getMessage());
        verify(notes, never()).createNote(any(), any());
    }

    @Test
    void carbsAbove300Rejected() {
        assertThrows(QuickLogRejectedException.class,
                () -> writer.write(user, new QuickLogCommand(MEAL, null, 301.0, AT), msg));
        verify(notes, never()).createNote(any(), any());
    }

    @Test
    void undoDeletesCreatedNotes() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        writer.undo(user, new AssistantNoteWriter.WriteResult(List.of(a, b), UUID.randomUUID()));
        verify(notes).deleteNote(user, a);
        verify(notes).deleteNote(user, b);
        verifyNoInteractions(hypo);
    }

    @Test
    void fastSugarFallsBackWhenEventAlreadyConfirmedByTheTimeConfirmRuns() {
        UUID eventId = UUID.randomUUID();
        when(hypo.findOpenEvent(user)).thenReturn(Optional.of(
                new HypoEventDTO(eventId, 3.2, "OPEN", null, AT, null)));
        when(hypo.confirm(user, eventId, 15.0, msg)).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "409 Hypo event is no longer open"));

        var r = writer.write(user, new QuickLogCommand(FAST_SUGAR, null, 15.0, AT), msg);

        assertEquals(Note.TYPE_HYPO_TREATMENT, captureRequest().getType());
        assertNull(r.hypoEventId());
        assertEquals(1, r.noteIds().size());
    }
}
