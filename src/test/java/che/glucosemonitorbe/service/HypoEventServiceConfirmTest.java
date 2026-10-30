package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshot;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotRepository;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotService;
import che.glucosemonitorbe.assistant.snapshot.SnapshotForecaster;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HypoEventServiceConfirmTest {

    private static final UUID USER_ID  = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID NOTE_ID  = UUID.randomUUID();

    private HypoEventRepository repository;
    private NoteRepository noteRepository;
    private HypoEventService service;
    private final UserSettingsService userSettingsService = mock(UserSettingsService.class);

    @BeforeEach
    void setUp() {
        repository = mock(HypoEventRepository.class);
        noteRepository = mock(NoteRepository.class);
        FeatureToggleConfig config = new FeatureToggleConfig();
        config.setHypoRescueLoggingEnabled(true);
        service = new HypoEventService(repository, noteRepository, config,
                org.mockito.Mockito.mock(che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotTrigger.class),
                userSettingsService);
        when(repository.save(any(HypoEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(NOTE_ID);
            return n;
        });
    }

    @Test
    void confirmCreatesAHypoTreatmentNoteAndLinksIt() {
        when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(openEvent()));

        HypoEventDTO dto = service.confirm(USER_ID, EVENT_ID, 15.0);

        ArgumentCaptor<Note> note = ArgumentCaptor.forClass(Note.class);
        verify(noteRepository).save(note.capture());
        assertThat(note.getValue().getType()).isEqualTo(Note.TYPE_HYPO_TREATMENT);
        assertThat(note.getValue().getCarbs()).isEqualTo(15.0);
        assertThat(note.getValue().getInsulin()).isZero();
        assertThat(dto.state()).isEqualTo("CONFIRMED");
        assertThat(dto.noteId()).isEqualTo(NOTE_ID);
    }

    /**
     * Double-logging rescue carbs is a safety problem, not just a data one: the phantom carbs
     * suppress the next genuine prompt and inflate the prediction while the user is still low.
     */
    @Test
    void confirmingTwiceCreatesExactlyOneNote() {
        HypoEvent alreadyConfirmed = openEvent();
        alreadyConfirmed.setState(State.CONFIRMED);
        alreadyConfirmed.setNoteId(NOTE_ID);
        when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(alreadyConfirmed));

        HypoEventDTO dto = service.confirm(USER_ID, EVENT_ID, 15.0);

        verify(noteRepository, never()).save(any());
        assertThat(dto.noteId()).isEqualTo(NOTE_ID);
        assertThat(dto.state()).isEqualTo("CONFIRMED");
    }

    @Test
    void taggedConfirmOnAlreadyConfirmedEventIsConflictNotIdempotent() {
        HypoEvent alreadyConfirmed = openEvent();
        alreadyConfirmed.setState(State.CONFIRMED);
        alreadyConfirmed.setNoteId(NOTE_ID);
        when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(alreadyConfirmed));

        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 15.0, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(noteRepository, never()).save(any());
    }

    @Test
    void confirmOnADismissedEventIsRejected() {
        HypoEvent dismissed = openEvent();
        dismissed.setState(State.DISMISSED);
        when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(dismissed));

        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 15.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void anotherUsersEventIsNotFound() {
        HypoEvent someoneElses = openEvent();
        someoneElses.setUserId(UUID.randomUUID());
        when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 15.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void gramsOutsideTheAllowedRangeIsRejected() {
        when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(openEvent()));

        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 0.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 101.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void dismissResolvesWithoutCreatingANote() {
        when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(openEvent()));

        HypoEventDTO dto = service.dismiss(USER_ID, EVENT_ID);

        verify(noteRepository, never()).save(any());
        assertThat(dto.state()).isEqualTo("DISMISSED");
    }

    /**
     * The in-memory idempotency check above only guards a single thread. Two requests racing
     * on the same event both read via plain {@code findById} would both see OPEN and both write
     * a note; pinning the locking finder here protects against a future refactor silently
     * dropping it and reintroducing that race. See
     * {@code HypoEventServiceConcurrencyIntegrationTest} for the real cross-connection race.
     */
    @Test
    void confirmTakesAPessimisticWriteLockRatherThanAPlainRead() {
        when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(openEvent()));

        service.confirm(USER_ID, EVENT_ID, 15.0);

        verify(repository).findByIdForUpdate(EVENT_ID);
        verify(repository, never()).findById(any());
    }

    @Test
    void dismissTakesAPessimisticWriteLockRatherThanAPlainRead() {
        when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(openEvent()));

        service.dismiss(USER_ID, EVENT_ID);

        verify(repository).findByIdForUpdate(EVENT_ID);
        verify(repository, never()).findById(any());
    }

    /**
     * The rescue note's timestamp must be the user's wall time, not the JVM's: notes.timestamp is
     * user wall time everywhere else, and the forecast-snapshot backdating check compares against
     * it. JVM on UTC, user in Tbilisi (UTC+4, no DST).
     */
    @Test
    void rescueNoteIsStampedInUserWallTimeAndGetsASnapshot() {
        java.util.TimeZone jvmZone = java.util.TimeZone.getDefault();
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        try {
            UserSettingsDTO settings = new UserSettingsDTO();
            settings.setTimezone("Asia/Tbilisi");
            when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settings);
            when(repository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(openEvent()));

            service.confirm(USER_ID, EVENT_ID, 15.0);

            ArgumentCaptor<Note> note = ArgumentCaptor.forClass(Note.class);
            verify(noteRepository).save(note.capture());
            LocalDateTime userNow = LocalDateTime.now(ZoneId.of("Asia/Tbilisi"));
            assertThat(Duration.between(note.getValue().getTimestamp(), userNow).abs())
                    .isLessThan(Duration.ofSeconds(1));

            // And the freezer, on the same user clock, does not treat it as backdated.
            ForecastSnapshotRepository snapshots = mock(ForecastSnapshotRepository.class);
            SnapshotForecaster forecaster = mock(SnapshotForecaster.class);
            when(snapshots.findByNoteId(NOTE_ID)).thenReturn(Optional.empty());
            when(forecaster.forecast(eq(USER_ID), any(), any())).thenReturn(Optional.of(
                    new SnapshotForecaster.FrozenForecast("hovorka", null, 3.4,
                            List.of(new SnapshotForecaster.Point(5, 3.6)))));
            when(noteRepository.findByIdAndUserId(NOTE_ID, USER_ID)).thenReturn(Optional.of(note.getValue()));
            ForecastSnapshotService freezer = new ForecastSnapshotService(snapshots, noteRepository, forecaster,
                    userSettingsService, new ObjectMapper());

            freezer.onNoteSaved(USER_ID, NOTE_ID, freezer.wallTime(USER_ID, Instant.now()));

            verify(snapshots).save(any(ForecastSnapshot.class));
        } finally {
            java.util.TimeZone.setDefault(jvmZone);
        }
    }

    private HypoEvent openEvent() {
        HypoEvent e = HypoEvent.builder()
                .userId(USER_ID).triggerGlucoseMmol(3.4).state(State.OPEN).build();
        e.setId(EVENT_ID);
        e.setDetectedAt(LocalDateTime.now().minusMinutes(2));
        return e;
    }
}
