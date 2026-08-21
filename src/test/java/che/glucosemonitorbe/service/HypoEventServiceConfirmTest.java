package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HypoEventServiceConfirmTest {

    private static final UUID USER_ID  = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID NOTE_ID  = UUID.randomUUID();

    private HypoEventRepository repository;
    private NoteRepository noteRepository;
    private HypoEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(HypoEventRepository.class);
        noteRepository = mock(NoteRepository.class);
        FeatureToggleConfig config = new FeatureToggleConfig();
        config.setHypoRescueLoggingEnabled(true);
        service = new HypoEventService(repository, noteRepository, config);
        when(repository.save(any(HypoEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(NOTE_ID);
            return n;
        });
    }

    @Test
    void confirmCreatesAHypoTreatmentNoteAndLinksIt() {
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(openEvent()));

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
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(alreadyConfirmed));

        HypoEventDTO dto = service.confirm(USER_ID, EVENT_ID, 15.0);

        verify(noteRepository, never()).save(any());
        assertThat(dto.noteId()).isEqualTo(NOTE_ID);
        assertThat(dto.state()).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmOnADismissedEventIsRejected() {
        HypoEvent dismissed = openEvent();
        dismissed.setState(State.DISMISSED);
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(dismissed));

        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 15.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void anotherUsersEventIsNotFound() {
        HypoEvent someoneElses = openEvent();
        someoneElses.setUserId(UUID.randomUUID());
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 15.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void gramsOutsideTheAllowedRangeIsRejected() {
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(openEvent()));

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
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(openEvent()));

        HypoEventDTO dto = service.dismiss(USER_ID, EVENT_ID);

        verify(noteRepository, never()).save(any());
        assertThat(dto.state()).isEqualTo("DISMISSED");
    }

    private HypoEvent openEvent() {
        HypoEvent e = HypoEvent.builder()
                .userId(USER_ID).triggerGlucoseMmol(3.4).state(State.OPEN).build();
        e.setId(EVENT_ID);
        e.setDetectedAt(LocalDateTime.now().minusMinutes(2));
        return e;
    }
}
