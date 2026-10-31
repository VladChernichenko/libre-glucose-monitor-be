package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.dto.ConfirmGlycemicEventRequest;
import che.glucosemonitorbe.dto.GlycemicEventDTO;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.repository.GlycemicEventHistoryRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GlycemicEventServiceTest {

    private final GlycemicEventRepository events = mock(GlycemicEventRepository.class);
    private final GlycemicEventHistoryRepository history = mock(GlycemicEventHistoryRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final GlycemicEventHistoryRecorder recorder = mock(GlycemicEventHistoryRecorder.class);
    private final GlycemicLearnerTrainingCache cache = mock(GlycemicLearnerTrainingCache.class);
    private final org.springframework.context.ApplicationEventPublisher publisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private GlycemicEventService service;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GlycemicEventService(events, history, notes, settings, new GlycemicEventProperties(), recorder, cache,
                org.mockito.Mockito.mock(che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotTrigger.class),
                publisher);
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(events.findByLinkedEventId(any())).thenReturn(List.of());
    }

    private GlycemicEvent stored(UUID owner, Category c, Phase p, State s) {
        GlycemicEvent e = GlycemicEvent.builder().id(UUID.randomUUID()).userId(owner).category(c).detectorCategory(c)
                .direction(c.direction()).phase(p).onsetAt(LocalDateTime.now().minusHours(1))
                .windowStart(LocalDateTime.now().minusHours(1)).windowEnd(LocalDateTime.now())
                .meanResidualMmol(3.0).state(s).build();
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        return e;
    }

    private HttpStatus status(Runnable r) {
        return (HttpStatus) assertThrows(ResponseStatusException.class, r::run).getStatusCode();
    }

    @Test void foreignEventIs404() {
        GlycemicEvent e = stored(UUID.randomUUID(), Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, e.getId(), null))).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void resolvedEventIs409BeforeAnyValidation() {
        GlycemicEvent e = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.DISMISSED);
        assertThat(status(() -> service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest("NOPE", 999.0, null))))
                .isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void unknownCategoryIs422() {
        GlycemicEvent e = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest("NOPE", null, null))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
    @Test void wrongDirectionIs422() {
        GlycemicEvent e = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest("UNLOGGED_INSULIN", null, null))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
    @Test void provisionalWithoutCategoryIs422() {
        GlycemicEvent e = stored(user, Category.STRESS, Phase.PROVISIONAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, e.getId(), null))).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
    @Test void backfillMustMatchCategory() {
        GlycemicEvent stress = stored(user, Category.STRESS, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, stress.getId(), new ConfirmGlycemicEventRequest(null, 20.0, null))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        GlycemicEvent food = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, food.getId(), new ConfirmGlycemicEventRequest(null, null, 2.0))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(notes, never()).save(any());
    }
    @Test void backfilledInsulinPublishesRapidInsulinLogged() {
        GlycemicEvent e = stored(user, Category.UNLOGGED_INSULIN, Phase.FINAL, State.OPEN);
        UUID noteId = UUID.randomUUID();
        when(notes.save(any())).thenAnswer(inv -> {
            che.glucosemonitorbe.entity.Note n = inv.getArgument(0);
            n.setId(noteId);
            return n;
        });
        service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest(null, null, 2.0));
        verify(publisher).publishEvent(new che.glucosemonitorbe.domain.RapidInsulinLogged(user, noteId, null));
    }
    @Test void backfilledCarbsPublishNothing() {
        GlycemicEvent e = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        when(notes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest(null, 20.0, null));
        verify(publisher, never()).publishEvent(any(Object.class));
    }
    @Test void relabelSetsUserLabelKeepsDetectorCategoryAndFinalises() {
        GlycemicEvent e = stored(user, Category.STRESS, Phase.PROVISIONAL, State.OPEN);
        GlycemicEventDTO dto = service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest("LIPODYSTROPHY", null, null));
        assertThat(dto.category()).isEqualTo("LIPODYSTROPHY");
        assertThat(dto.userLabel()).isEqualTo("LIPODYSTROPHY");
        assertThat(dto.detectorCategory()).isEqualTo("STRESS");
        assertThat(dto.phase()).isEqualTo("FINAL");
        assertThat(dto.state()).isEqualTo("CONFIRMED");
        verify(recorder).record(any(), eq(Kind.RELABELLED), eq(Category.STRESS), any(), isNull());
        verify(cache).invalidate(user);
    }
    @Test void dismissingAParentDismissesItsChildren() {
        GlycemicEvent parent = stored(user, Category.ABSORPTION_DELAY, Phase.FINAL, State.OPEN);
        GlycemicEvent child = GlycemicEvent.builder().id(UUID.randomUUID()).userId(user)
                .category(Category.ABSORPTION_DELAY).direction(Direction.FALL).linkedEventId(parent.getId())
                .state(State.OPEN).build();
        when(events.findByLinkedEventId(parent.getId())).thenReturn(List.of(child));
        service.dismiss(user, parent.getId());
        assertThat(child.getState()).isEqualTo(State.DISMISSED);
    }
    @Test void relabellingAParentRelabelsItsChildren() {
        GlycemicEvent parent = stored(user, Category.ABSORPTION_DELAY, Phase.FINAL, State.OPEN);
        GlycemicEvent child = GlycemicEvent.builder().id(UUID.randomUUID()).userId(user)
                .category(Category.ABSORPTION_DELAY).detectorCategory(Category.ABSORPTION_DELAY)
                .direction(Direction.FALL).linkedEventId(parent.getId()).state(State.OPEN).build();
        when(events.findByLinkedEventId(parent.getId())).thenReturn(List.of(child));
        service.confirm(user, parent.getId(), new ConfirmGlycemicEventRequest("LIPODYSTROPHY", null, null));
        assertThat(child.getState()).isEqualTo(State.CONFIRMED);
        assertThat(child.getCategory()).isEqualTo(Category.LIPODYSTROPHY);
        assertThat(child.getUserLabel()).isEqualTo(Category.LIPODYSTROPHY);
        assertThat(child.getDetectorCategory()).isEqualTo(Category.ABSORPTION_DELAY);
    }
    @Test void aConcurrentScanWriteDuringConfirmOrDismissIs409() {
        doThrow(new ObjectOptimisticLockingFailureException(GlycemicEvent.class, UUID.randomUUID())).when(events).flush();
        GlycemicEvent a = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, a.getId(), null))).isEqualTo(HttpStatus.CONFLICT);
        GlycemicEvent b = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.dismiss(user, b.getId()))).isEqualTo(HttpStatus.CONFLICT);
        verify(cache, never()).invalidate(any());
    }
}
