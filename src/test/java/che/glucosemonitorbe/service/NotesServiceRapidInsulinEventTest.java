package che.glucosemonitorbe.service;

import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotTrigger;
import che.glucosemonitorbe.domain.RapidInsulinLogged;
import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.mapper.NoteMapper;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.observer.GlucoseAlertService;
import che.glucosemonitorbe.storage.NotePhotoStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A created rapid-insulin note announces itself so a PLANNED meal plan cannot be stacked on it. */
class NotesServiceRapidInsulinEventTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID NOTE = UUID.randomUUID();
    private final NoteRepository notes = mock(NoteRepository.class);
    private final NoteMapper mapper = mock(NoteMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private NotesService service;

    @BeforeEach
    void setUp() {
        when(notes.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(NOTE);
            return n;
        });
        when(mapper.toDto(any(Note.class))).thenReturn(new NoteDto());
        service = new NotesService(notes, mapper, mock(NutritionEnrichmentService.class), new ObjectMapper(),
                mock(GlucoseAlertService.class), mock(UserService.class), mock(NotePhotoStorageService.class),
                mock(ForecastSnapshotTrigger.class), events);
    }

    private CreateNoteRequest note(double insulin, double carbs, String type, UUID chatMessageId) {
        CreateNoteRequest r = new CreateNoteRequest();
        r.setTimestamp(LocalDateTime.of(2026, 10, 31, 12, 0));
        r.setInsulin(insulin);
        r.setCarbs(carbs);
        r.setType(type);
        r.setMeal("x");
        r.setChatMessageId(chatMessageId);
        r.setNutritionProfile("{\"absorptionMode\":\"DEFAULT_DECAY\"}");
        return r;
    }

    @Test
    @DisplayName("a rapid dose (Notes tab, quick log, Took) publishes RapidInsulinLogged with its chat message")
    void rapidDosePublishes() {
        UUID chat = UUID.randomUUID();
        service.createNote(USER, note(3.0, 0.0, Note.TYPE_NORMAL, chat));

        verify(events).publishEvent(new RapidInsulinLogged(USER, NOTE, chat));
    }

    @Test
    @DisplayName("a dose with a meal publishes too")
    void doseWithMealPublishes() {
        service.createNote(USER, note(2.0, 40.0, Note.TYPE_NORMAL, null));

        verify(events).publishEvent(new RapidInsulinLogged(USER, NOTE, null));
    }

    @Test
    @DisplayName("long-acting, zero-insulin and meal-only notes publish nothing")
    void nonRapidPublishesNothing() {
        service.createNote(USER, note(18.0, 0.0, Note.TYPE_LONG_ACTING, null));
        service.createNote(USER, note(0.0, 45.0, Note.TYPE_NORMAL, null));

        verify(events, never()).publishEvent(any(Object.class));
        assertEquals(2, mockingDetails(notes).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save")).count());
    }
}
