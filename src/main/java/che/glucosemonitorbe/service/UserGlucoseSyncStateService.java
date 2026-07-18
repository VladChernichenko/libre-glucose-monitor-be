package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.repository.UserGlucoseSyncStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class UserGlucoseSyncStateService {

    public static final String STATUS_NEW_DATA = "NEW_DATA";
    public static final String STATUS_NO_CHANGE = "NO_CHANGE";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_SKIPPED_BACKOFF = "SKIPPED_BACKOFF";

    private final UserGlucoseSyncStateRepository repository;

    @Transactional
    public UserGlucoseSyncState getOrCreate(UUID userId) {
        return repository.findByUserId(userId).orElseGet(() -> {
            try {
                // BUG C2 fix: concurrent callers can both find nothing and both try to insert.
                // Catch the unique-constraint violation and re-query the row the other thread committed.
                return repository.save(
                        UserGlucoseSyncState.builder()
                                .userId(userId)
                                .consecutiveNoChangeCount(0)
                                .updatedAt(LocalDateTime.now())
                                .build()
                );
            } catch (DataIntegrityViolationException e) {
                // Another thread won the insert race - re-query to return their committed row.
                // If the re-query also returns empty (test environment / rollback), fall back
                // to an unsaved default so the caller always receives a non-null state.
                return repository.findByUserId(userId)
                        .orElseGet(() -> UserGlucoseSyncState.builder()
                                .userId(userId)
                                .consecutiveNoChangeCount(0)
                                .updatedAt(LocalDateTime.now())
                                .build());
            }
        });
    }

    @Transactional
    public void markSkippedBackoff(UUID userId, LocalDateTime now) {
        UserGlucoseSyncState state = getOrCreate(userId);
        state.setLastCheckedAt(now);
        state.setLastStatus(STATUS_SKIPPED_BACKOFF);
        state.setUpdatedAt(now);
        repository.save(state);
    }

    @Transactional
    public void markError(UUID userId, LocalDateTime now) {
        UserGlucoseSyncState state = getOrCreate(userId);
        state.setLastCheckedAt(now);
        state.setLastStatus(STATUS_ERROR);
        state.setUpdatedAt(now);
        // BUG C3 fix: set next poll time to backoff after error (5 min)
        state.setNextPollAt(now.plusMinutes(5));
        repository.save(state);
    }

    @Transactional
    public void markNoChange(UUID userId, LocalDateTime now, LocalDateTime nextPollAt) {
        UserGlucoseSyncState state = getOrCreate(userId);
        state.setLastCheckedAt(now);
        state.setNextPollAt(nextPollAt);
        state.setConsecutiveNoChangeCount((state.getConsecutiveNoChangeCount() == null ? 0 : state.getConsecutiveNoChangeCount()) + 1);
        state.setLastStatus(STATUS_NO_CHANGE);
        state.setUpdatedAt(now);
        repository.save(state);
    }

    @Transactional
    public void markNewData(UUID userId, long newestTimestamp, LocalDateTime now, LocalDateTime nextPollAt) {
        UserGlucoseSyncState state = getOrCreate(userId);
        state.setLastCheckedAt(now);
        state.setLastNewDataAt(now);
        state.setLastSeenEntryTimestamp(newestTimestamp);
        state.setNextPollAt(nextPollAt);
        state.setConsecutiveNoChangeCount(0);
        state.setLastStatus(STATUS_NEW_DATA);
        state.setUpdatedAt(now);
        repository.save(state);
    }
}
