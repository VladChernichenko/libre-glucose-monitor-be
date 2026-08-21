package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.repository.HypoEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HypoEventServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private HypoEventRepository repository;
    private FeatureToggleConfig config;
    private HypoEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(HypoEventRepository.class);
        config = new FeatureToggleConfig();
        config.setHypoRescueLoggingEnabled(true);
        service = new HypoEventService(repository, config);
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

        service.onGlucoseReading(USER_ID, 4.6);

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

    private HypoEvent openEvent(LocalDateTime detectedAt) {
        HypoEvent e = HypoEvent.builder()
                .userId(USER_ID).triggerGlucoseMmol(3.4).state(State.OPEN).build();
        e.setId(UUID.randomUUID());
        e.setDetectedAt(detectedAt);
        return e;
    }
}
