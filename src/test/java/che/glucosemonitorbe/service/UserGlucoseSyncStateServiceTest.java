package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.repository.UserGlucoseSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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

    // ── C3: markError must set nextPollAt for backoff ─────────────────────────

    /**
     * BUG: C3 — UserGlucoseSyncStateService.markError does not set nextPollAt.
     * Without nextPollAt the scheduler sees a null/past time and immediately re-polls,
     * causing a tight error loop that hammers the Nightscout endpoint.
     *
     * Expected: after markError, the saved state must have nextPollAt set to a
     * point in the future relative to the current time (backoff).
     *
     * This test FAILS because the current markError implementation does not set nextPollAt.
     */
    @Test
    void c3_markError_mustSetNextPollAtToFutureTimeForBackoff() {
        UUID userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        UserGlucoseSyncState existing = UserGlucoseSyncState.builder()
                .userId(userId)
                .consecutiveNoChangeCount(0)
                .updatedAt(now)
                .build();

        when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(repository.save(any(UserGlucoseSyncState.class))).thenAnswer(i -> i.getArgument(0));

        service.markError(userId, now);

        ArgumentCaptor<UserGlucoseSyncState> captor = ArgumentCaptor.forClass(UserGlucoseSyncState.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        UserGlucoseSyncState saved = captor.getValue();

        // BUG: nextPollAt is null in current implementation — this FAILS
        assertThat(saved.getNextPollAt())
                .as("markError must set nextPollAt to a future time for error backoff")
                .isNotNull()
                .isAfter(now);
    }

    // ── C2: getOrCreate has a TOCTOU race condition ───────────────────────────

    /**
     * BUG: C2 — UserGlucoseSyncStateService.getOrCreate uses findByUserId → if empty,
     * save(newState). Two concurrent threads can both see empty and both call save,
     * causing a DataIntegrityViolationException on the unique constraint.
     *
     * Expected: the service must handle this case gracefully — on a unique constraint
     * violation it should retry the find and return the existing record rather than
     * propagating DataIntegrityViolationException to the caller.
     *
     * This test stubs the repository to simulate the race:
     *   - findByUserId always returns empty (both threads see the gap)
     *   - first save succeeds; second save throws DataIntegrityViolationException
     *
     * The test verifies that the service does NOT propagate the exception.
     * It FAILS because the current service has no retry/catch logic.
     */
    @Test
    void c2_getOrCreate_concurrentInsert_mustNotThrowDataIntegrityViolation() {
        UUID userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        AtomicInteger saveCount = new AtomicInteger(0);

        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.save(any(UserGlucoseSyncState.class))).thenAnswer(inv -> {
            int callNum = saveCount.incrementAndGet();
            if (callNum > 1) {
                // Simulate the second concurrent thread hitting the unique constraint
                throw new DataIntegrityViolationException("unique constraint violation on user_id");
            }
            return inv.getArgument(0);
        });

        // BUG: second call to getOrCreate propagates DataIntegrityViolationException
        // Expected: service catches it and returns the existing record gracefully
        org.assertj.core.api.Assertions.assertThatCode(() -> {
            service.getOrCreate(userId); // first call — succeeds
            service.getOrCreate(userId); // simulates concurrent second call — throws on save
        }).doesNotThrowAnyException();
    }
}
