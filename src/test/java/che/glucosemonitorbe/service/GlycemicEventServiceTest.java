package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlycemicEventServiceTest {

    private final GlycemicEventRepository events = mock(GlycemicEventRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final GlycemicEventHistoryRecorder recorder = mock(GlycemicEventHistoryRecorder.class);
    private GlycemicEventService service;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GlycemicEventService(events, notes, recorder);
        ReflectionTestUtils.setField(service, "maxBackfillCarbs", 300.0);
        ReflectionTestUtils.setField(service, "maxBackfillInsulin", 50.0);
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private GlycemicEvent open(UUID owner) {
        return GlycemicEvent.builder().id(UUID.randomUUID()).userId(owner)
                .category(Category.UNLOGGED_FOOD).direction(Direction.RISE)
                .windowStart(LocalDateTime.now().minusHours(1)).windowEnd(LocalDateTime.now())
                .meanResidualMmol(3.0).state(State.OPEN).build();
    }

    @Test
    void confirmingAnotherUsersEventIs404() {
        GlycemicEvent e = open(UUID.randomUUID());
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm(user, e.getId(), null, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void confirmingAResolvedEventIs409() {
        GlycemicEvent e = open(user);
        e.setState(State.DISMISSED);
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm(user, e.getId(), 30.0, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void backfillAboveCapIs422AndWritesNoNote() {
        GlycemicEvent e = open(user);
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm(user, e.getId(), 400.0, null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        verify(notes, never()).save(any());
    }

    @Test
    void dismissMarksDismissed() {
        GlycemicEvent e = open(user);
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        assertEquals("DISMISSED", service.dismiss(user, e.getId()).state());
        verify(recorder).record(same(e), eq(Kind.DISMISSED), eq(Category.UNLOGGED_FOOD), eq(Actor.USER), isNull());
    }

    @Test
    void confirmRecordsConfirmedHistoryByUser() {
        GlycemicEvent e = open(user);
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        assertEquals("CONFIRMED", service.confirm(user, e.getId(), null, null).state());
        verify(recorder).record(same(e), eq(Kind.CONFIRMED), eq(Category.UNLOGGED_FOOD), eq(Actor.USER), isNull());
    }
}
