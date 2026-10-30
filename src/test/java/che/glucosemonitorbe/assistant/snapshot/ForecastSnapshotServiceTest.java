package che.glucosemonitorbe.assistant.snapshot;

import che.glucosemonitorbe.assistant.snapshot.SnapshotForecaster.FrozenForecast;
import che.glucosemonitorbe.assistant.snapshot.SnapshotForecaster.Point;
import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.mapper.NoteMapper;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.NotesService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.observer.GlucoseAlertService;
import che.glucosemonitorbe.storage.NotePhotoStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForecastSnapshotServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID NOTE = UUID.randomUUID();
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Mock ForecastSnapshotRepository repository;
    @Mock NoteRepository noteRepository;
    @Mock SnapshotForecaster forecaster;
    @Mock UserSettingsService userSettingsService;

    private ForecastSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new ForecastSnapshotService(repository, noteRepository, forecaster, userSettingsService,
                new ObjectMapper());
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setTimezone("Europe/Berlin");
        when(userSettingsService.getUserSettings(USER)).thenReturn(settings);
        when(repository.findByNoteId(NOTE)).thenReturn(Optional.empty());
        when(repository.save(any(ForecastSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(forecaster.forecast(eq(USER), any(LocalDateTime.class), any()))
                .thenReturn(Optional.of(new FrozenForecast("hovorka", null, 7.2,
                        List.of(new Point(5, 7.3), new Point(240, 6.1)))));
    }

    @Test
    void anchorNoteGetsSnapshot() {
        stubNote(note(NOON, 0.0, 4.0, Note.TYPE_NORMAL));

        service.onNoteSaved(USER, NOTE, NOON);

        ArgumentCaptor<ForecastSnapshot> saved = ArgumentCaptor.forClass(ForecastSnapshot.class);
        verify(repository).save(saved.capture());
        ForecastSnapshot s = saved.getValue();
        assertThat(s.getUserId()).isEqualTo(USER);
        assertThat(s.getNoteId()).isEqualTo(NOTE);
        assertThat(s.getMadeAt()).isEqualTo(NOON.atZone(BERLIN).toInstant());
        assertThat(s.getEngine()).isEqualTo("hovorka");
        assertThat(s.getStartGlucose()).isEqualTo(7.2);
        assertThat(s.getPoints()).contains("\"minute\":240").contains("\"mmol\":6.1");
        assertThat(s.isExcluded()).isFalse();
        verify(forecaster).forecast(eq(USER), eq(NOON), any());
    }

    @Test
    void carbsAndHypoTreatmentAreAnchorsButSmallSnackIsNot() {
        stubNote(note(NOON, 10.0, 0.0, Note.TYPE_NORMAL));
        service.onNoteSaved(USER, NOTE, NOON);
        stubNote(note(NOON, 8.0, 0.0, Note.TYPE_HYPO_TREATMENT));
        service.onNoteSaved(USER, NOTE, NOON);
        stubNote(note(NOON, 9.0, 0.0, Note.TYPE_NORMAL));
        service.onNoteSaved(USER, NOTE, NOON);

        verify(repository, times(2)).save(any(ForecastSnapshot.class));
    }

    @Test
    void basalOnlyNoteGetsNone() {
        stubNote(note(NOON, 0.0, 14.0, Note.TYPE_LONG_ACTING));

        service.onNoteSaved(USER, NOTE, NOON);

        verify(repository, never()).save(any());
        verify(forecaster, never()).forecast(any(), any(), any());
    }

    @Test
    void backdatedMoreThan10MinGetsNone() {
        stubNote(note(LocalDateTime.of(2026, 10, 30, 11, 45), 40.0, 4.0, Note.TYPE_NORMAL));

        service.onNoteSaved(USER, NOTE, NOON);

        verify(repository, never()).save(any());
        verify(forecaster, never()).forecast(any(), any(), any());
    }

    @Test
    void editWithin15MinRefreezes() {
        ForecastSnapshot existing = existing();
        when(repository.findByNoteId(NOTE)).thenReturn(Optional.of(existing));
        stubNote(note(NOON, 50.0, 5.0, Note.TYPE_NORMAL));
        LocalDateTime editAt = NOON.plusMinutes(10);

        service.onNoteUpdated(USER, NOTE, NOON, editAt);

        ArgumentCaptor<ForecastSnapshot> saved = ArgumentCaptor.forClass(ForecastSnapshot.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(existing.getId());
        assertThat(saved.getValue().getPoints()).contains("\"minute\":240");
        assertThat(saved.getValue().getMadeAt()).isEqualTo(editAt.atZone(BERLIN).toInstant());
        assertThat(saved.getValue().isExcluded()).isFalse();
        verify(forecaster).forecast(eq(USER), eq(editAt), any());
    }

    @Test
    void editWithin15MinThatRemovesTheAnchorDeletesSnapshot() {
        when(repository.findByNoteId(NOTE)).thenReturn(Optional.of(existing()));
        stubNote(note(NOON, 0.0, 0.0, Note.TYPE_NORMAL));

        service.onNoteUpdated(USER, NOTE, NOON, NOON.plusMinutes(5));

        verify(repository).deleteByNoteId(NOTE);
        verify(repository, never()).save(any());
    }

    @Test
    void editAfter15MinMarksExcluded() {
        ForecastSnapshot existing = existing();
        when(repository.findByNoteId(NOTE)).thenReturn(Optional.of(existing));
        stubNote(note(NOON, 50.0, 5.0, Note.TYPE_NORMAL));

        service.onNoteUpdated(USER, NOTE, NOON, NOON.plusMinutes(20));

        ArgumentCaptor<ForecastSnapshot> saved = ArgumentCaptor.forClass(ForecastSnapshot.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isExcluded()).isTrue();
        assertThat(saved.getValue().getPoints()).isEqualTo("[{\"minute\":5,\"mmol\":7.0}]");
        verify(forecaster, never()).forecast(any(), any(), any());
    }

    @Test
    void deleteRemovesSnapshot() {
        service.onNoteDeleted(NOTE);

        verify(repository).deleteByNoteId(NOTE);
    }

    @Test
    void saveSucceedsWhenSnapshotThrows() {
        ForecastSnapshotService throwing = mock(ForecastSnapshotService.class);
        doThrow(new IllegalStateException("boom")).when(throwing).onNoteSaved(any(), any(), any());
        when(throwing.wallTime(any(), any())).thenReturn(NOON);
        ForecastSnapshotTrigger trigger = new ForecastSnapshotTrigger(throwing, Runnable::run);

        NoteRepository notes = mock(NoteRepository.class);
        NoteMapper mapper = mock(NoteMapper.class);
        when(notes.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(NOTE);
            return n;
        });
        when(mapper.toDto(any(Note.class))).thenReturn(new NoteDto());
        NotesService notesService = new NotesService(notes, mapper, mock(NutritionEnrichmentService.class),
                new ObjectMapper(), mock(GlucoseAlertService.class), mock(UserService.class),
                mock(NotePhotoStorageService.class), trigger);
        CreateNoteRequest req = new CreateNoteRequest();
        req.setTimestamp(NOON);
        req.setCarbs(40.0);
        req.setInsulin(0.0);
        req.setMeal("Lunch");
        req.setNutritionProfile("{\"absorptionMode\":\"DEFAULT_DECAY\"}");

        assertThatCode(() -> assertThat(notesService.createNote(USER, req)).isNotNull())
                .doesNotThrowAnyException();
        verify(throwing).onNoteSaved(eq(USER), eq(NOTE), eq(NOON));
        verify(notes).save(any(Note.class));
    }

    @Test
    void triggerRunsOnlyAfterCommit() {
        ForecastSnapshotService svc = mock(ForecastSnapshotService.class);
        when(svc.wallTime(any(), any())).thenReturn(NOON);
        ForecastSnapshotTrigger trigger = new ForecastSnapshotTrigger(svc, Runnable::run);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            trigger.noteSaved(USER, NOTE);
            verify(svc, never()).onNoteSaved(any(), any(), any());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(svc).onNoteSaved(USER, NOTE, NOON);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ForecastSnapshot existing() {
        ForecastSnapshot s = new ForecastSnapshot();
        s.setId(UUID.randomUUID());
        s.setUserId(USER);
        s.setNoteId(NOTE);
        s.setMadeAt(NOON.atZone(BERLIN).toInstant());
        s.setEngine("hovorka");
        s.setPoints("[{\"minute\":5,\"mmol\":7.0}]");
        return s;
    }

    private void stubNote(Note n) {
        when(noteRepository.findByIdAndUserId(NOTE, USER)).thenReturn(Optional.of(n));
    }

    private static Note note(LocalDateTime at, double carbs, double insulin, String type) {
        Note n = new Note(USER, at, carbs, insulin, "Lunch");
        n.setId(NOTE);
        n.setType(type);
        return n;
    }
}
