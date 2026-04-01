package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.repository.UserGlucoseSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserGlucoseSyncStateServiceTest {

    @Mock
    private UserGlucoseSyncStateRepository repository;

    @InjectMocks
    private UserGlucoseSyncStateService service;

    @Test
    void markNoChangeShouldIncreaseCounterAndSetSlowNextPoll() {
        UUID userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.plusHours(1);
        UserGlucoseSyncState existing = UserGlucoseSyncState.builder()
                .userId(userId)
                .consecutiveNoChangeCount(2)
                .build();

        when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(repository.save(any(UserGlucoseSyncState.class))).thenAnswer(i -> i.getArgument(0));

        service.markNoChange(userId, now, next);

        ArgumentCaptor<UserGlucoseSyncState> captor = ArgumentCaptor.forClass(UserGlucoseSyncState.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        UserGlucoseSyncState saved = captor.getValue();
        assertEquals(3, saved.getConsecutiveNoChangeCount());
        assertEquals(next, saved.getNextPollAt());
        assertEquals(UserGlucoseSyncStateService.STATUS_NO_CHANGE, saved.getLastStatus());
    }
}
