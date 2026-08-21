package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.observer.HypoThresholds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HypoEventServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private HypoEventRepository repository;
    private NoteRepository noteRepository;
    private FeatureToggleConfig config;
    private HypoEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(HypoEventRepository.class);
        noteRepository = mock(NoteRepository.class);
        config = new FeatureToggleConfig();
        config.setHypoRescueLoggingEnabled(true);
        service = new HypoEventService(repository, noteRepository, config);
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.empty());
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.empty());
        when(repository.save(any(HypoEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void opensAnEventWhenGlucoseGoesBelowThreshold() {
        service.onGlucoseReading(USER_ID, 3.4);

        ArgumentCaptor<HypoEvent> saved = ArgumentCaptor.forClass(HypoEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(State.OPEN);
        assertThat(saved.getValue().getTriggerGlucoseMmol()).isEqualTo(3.4);
    }

    @Test
    void doesNotOpenAtOrAboveThreshold() {
        service.onGlucoseReading(USER_ID, 3.9);
        verify(repository, never()).save(any());
    }

    @Test
    void doesNotOpenASecondEventWhileOneIsOpen() {
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.of(openEvent(LocalDateTime.now().minusMinutes(5))));

        service.onGlucoseReading(USER_ID, 3.2);

        verify(repository, never()).save(any());
    }

    @Test
    void expiresAnOpenEventOnceGlucoseRecovers() {
        HypoEvent open = openEvent(LocalDateTime.now().minusMinutes(10));
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.of(open));

        // Exactly the recovery boundary: contract is ">= RECOVERY_MMOL", so 4.5 itself must
        // expire. A mutant weakening ">=" to ">" would still pass at 4.6; this pins it.
        service.onGlucoseReading(USER_ID, 4.5);

        assertThat(open.getState()).isEqualTo(State.EXPIRED);
        assertThat(open.getResolvedAt()).isNotNull();
        verify(repository).save(open);
    }

    /** Between 3.9 and 4.5 is the hysteresis band: neither open nor expire. */
    @Test
    void doesNotExpireInsideTheHysteresisBand() {
        HypoEvent open = openEvent(LocalDateTime.now().minusMinutes(10));
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.of(open));

        service.onGlucoseReading(USER_ID, 4.2);

        assertThat(open.getState()).isEqualTo(State.OPEN);
        verify(repository, never()).save(any());
    }

    @Test
    void suppressesANewEventWithinFifteenMinutesOfTheLastResolution() {
        HypoEvent recent = openEvent(LocalDateTime.now().minusMinutes(5));
        recent.setState(State.DISMISSED);
        recent.setResolvedAt(LocalDateTime.now().minusMinutes(5));
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.of(recent));

        service.onGlucoseReading(USER_ID, 3.2);

        verify(repository, never()).save(any());
    }

    /** Rule of 15: still low after the window, so prompt again - that is correct, not a nag. */
    @Test
    void promptsAgainOnceTheSuppressionWindowHasElapsed() {
        HypoEvent old = openEvent(LocalDateTime.now().minusMinutes(30));
        old.setState(State.DISMISSED);
        old.setResolvedAt(LocalDateTime.now().minusMinutes(20));
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.of(old));

        service.onGlucoseReading(USER_ID, 3.2);

        verify(repository).save(any(HypoEvent.class));
    }

    /**
     * An EXPIRED event does not suppress: glucose recovered on its own and then fell again, which
     * is a genuinely new hypo, not a re-prompt for the same one.
     */
    @Test
    void doesNotSuppressWhenTheLastEventExpiredRatherThanWasResolvedByTheUser() {
        HypoEvent expired = openEvent(LocalDateTime.now().minusMinutes(5));
        expired.setState(State.EXPIRED);
        expired.setResolvedAt(LocalDateTime.now().minusMinutes(5));
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.of(expired));

        service.onGlucoseReading(USER_ID, 3.2);

        verify(repository).save(any(HypoEvent.class));
    }

    @Test
    void doesNothingWhenTheFeatureIsDisabled() {
        config.setHypoRescueLoggingEnabled(false);
        service.onGlucoseReading(USER_ID, 3.2);
        verifyNoInteractions(repository);
    }

    // -- Age-out: an OPEN event must not live forever ---------------------------

    /**
     * An OPEN event is otherwise only closed by a recovery reading, and the detector needs two CGM
     * readings in a 20-minute window to produce one. A sensor change or an offline phone therefore
     * strands the row OPEN, and the client shows that stale prompt on next launch - possibly the
     * next morning - with a trigger glucose from hours ago. Confirming it then writes a
     * hypo_treatment note at now(), injecting phantom fast carbs into COB, Hovorka and the twin fit.
     */
    @Test
    void agesOutAnOpenEventOlderThanTheMaximumAndOpensAFreshOne() {
        HypoEvent stale = openEvent(LocalDateTime.now()
                .minusMinutes(HypoThresholds.MAX_OPEN_MINUTES + 1));
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.of(stale));

        service.onGlucoseReading(USER_ID, 3.2);

        assertThat(stale.getState()).isEqualTo(State.EXPIRED);
        assertThat(stale.getResolvedAt()).isNotNull();

        // ...and the still-low reading opens a current prompt carrying today's trigger glucose.
        ArgumentCaptor<HypoEvent> saved = ArgumentCaptor.forClass(HypoEvent.class);
        verify(repository, times(2)).save(saved.capture());
        HypoEvent fresh = saved.getAllValues().get(1);
        assertThat(fresh.getState()).isEqualTo(State.OPEN);
        assertThat(fresh.getTriggerGlucoseMmol()).isEqualTo(3.2);
    }

    /** One minute inside the bound is still a live prompt - pins the boundary against a mutant. */
    @Test
    void doesNotAgeOutAnOpenEventJustInsideTheMaximum() {
        HypoEvent recent = openEvent(LocalDateTime.now()
                .minusMinutes(HypoThresholds.MAX_OPEN_MINUTES - 1));
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.of(recent));

        service.onGlucoseReading(USER_ID, 3.2);

        assertThat(recent.getState()).isEqualTo(State.OPEN);
        verify(repository, never()).save(any());
    }

    /** The read path sweeps too: it is what runs when the CGM scan has stopped producing readings. */
    @Test
    void listAgesOutAStaleOpenEventAndOmitsItFromAnOpenOnlyQuery() {
        HypoEvent stale = openEvent(LocalDateTime.now()
                .minusMinutes(HypoThresholds.MAX_OPEN_MINUTES + 5));
        when(repository.findByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(new java.util.ArrayList<>(List.of(stale)));

        List<HypoEventDTO> open = service.list(USER_ID, State.OPEN);

        assertThat(stale.getState()).isEqualTo(State.EXPIRED);
        assertThat(open)
                .as("a prompt that is no longer live must never be handed to the client")
                .isEmpty();
    }

    @Test
    void confirmRefusesAStaleOpenEventRatherThanLoggingPhantomCarbs() {
        HypoEvent stale = openEvent(LocalDateTime.now()
                .minusMinutes(HypoThresholds.MAX_OPEN_MINUTES + 120));
        when(repository.findByIdForUpdate(stale.getId())).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.confirm(USER_ID, stale.getId(), 15.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no longer open");
        verifyNoInteractions(noteRepository);
        assertThat(stale.getState()).isEqualTo(State.EXPIRED);
    }

    // -- Suppression is ended by a recovery, not only by the clock ---------------

    /**
     * Spec sec.8 requires a new hypo after recovery to open a fresh prompt; sec.5 asks for a
     * 15-minute quiet window. Keying suppression only on elapsed time made them contradict: dismiss
     * at 3.8, recover to 4.6, crash to 3.2 twelve minutes later, and the patient got no prompt at
     * all. A recovery ends the episode, so it ends the window the episode owns.
     */
    @Test
    void aRecoveryReadingClearsTheSuppressionWindow() {
        HypoEvent dismissed = openEvent(LocalDateTime.now().minusMinutes(12));
        dismissed.setState(State.DISMISSED);
        dismissed.setResolvedAt(LocalDateTime.now().minusMinutes(12));
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.of(dismissed));

        // 4.6 -> recovered. Stamps recoveredAt; nothing is OPEN, so nothing is expired.
        service.onGlucoseReading(USER_ID, 4.6);
        assertThat(dismissed.getRecoveredAt()).isNotNull();

        // ...then a genuine relapse, still inside the 15-minute window.
        service.onGlucoseReading(USER_ID, 3.2);

        ArgumentCaptor<HypoEvent> saved = ArgumentCaptor.forClass(HypoEvent.class);
        verify(repository, times(2)).save(saved.capture());
        HypoEvent fresh = saved.getAllValues().get(1);
        assertThat(fresh.getState()).isEqualTo(State.OPEN);
        assertThat(fresh.getTriggerGlucoseMmol()).isEqualTo(3.2);
    }

    /**
     * The other half of the ruling: while the user has <em>not</em> recovered, the quiet window
     * still stands. Without this, the test above would also pass for "suppression deleted".
     */
    @Test
    void staysSuppressedWhenGlucoseNeverCameBackUp() {
        HypoEvent dismissed = openEvent(LocalDateTime.now().minusMinutes(12));
        dismissed.setState(State.DISMISSED);
        dismissed.setResolvedAt(LocalDateTime.now().minusMinutes(12));
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.of(dismissed));

        // Hysteresis-band reading: above the hypo floor but below recovery - not a recovery.
        service.onGlucoseReading(USER_ID, 4.2);
        assertThat(dismissed.getRecoveredAt()).isNull();

        service.onGlucoseReading(USER_ID, 3.2);

        verify(repository, never()).save(any());
    }

    /** The recovery stamp is written once, not on every in-range reading for the rest of time. */
    @Test
    void recoveryIsStampedOnlyOnce() {
        HypoEvent dismissed = openEvent(LocalDateTime.now().minusMinutes(12));
        dismissed.setState(State.DISMISSED);
        dismissed.setResolvedAt(LocalDateTime.now().minusMinutes(12));
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.of(dismissed));

        service.onGlucoseReading(USER_ID, 6.0);
        service.onGlucoseReading(USER_ID, 6.1);
        service.onGlucoseReading(USER_ID, 6.2);

        verify(repository, times(1)).save(dismissed);
    }

    private HypoEvent openEvent(LocalDateTime detectedAt) {
        HypoEvent e = HypoEvent.builder()
                .userId(USER_ID).triggerGlucoseMmol(3.4).state(State.OPEN).build();
        e.setId(UUID.randomUUID());
        e.setDetectedAt(detectedAt);
        return e;
    }
}
