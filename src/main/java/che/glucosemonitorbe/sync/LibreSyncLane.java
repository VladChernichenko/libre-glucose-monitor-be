package che.glucosemonitorbe.sync;

import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import che.glucosemonitorbe.service.UserGlucoseSyncStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** LibreLinkUp due-queue lane: credentialed active Libre users, 60 s lane pause on HTTP 429. */
@Component
public class LibreSyncLane extends SyncLane {

    static final Duration RATE_LIMIT_PAUSE = Duration.ofSeconds(60);
    static final Duration CIRCUIT_DEFAULT_RETRY = Duration.ofSeconds(120);

    private final UserDataSourceConfigRepository configRepository;
    private final LibreLinkUpSyncService syncService;

    @Autowired
    public LibreSyncLane(UserDataSourceConfigRepository configRepository,
                         LibreLinkUpSyncService syncService,
                         UserGlucoseSyncStateService state) {
        this(configRepository, syncService, state, Clock.systemUTC());
    }

    LibreSyncLane(UserDataSourceConfigRepository configRepository,
                  LibreLinkUpSyncService syncService,
                  UserGlucoseSyncStateService state,
                  Clock clock) {
        super("Libre", clock, state);
        this.configRepository = configRepository;
        this.syncService = syncService;
    }

    @Override
    protected Set<UUID> members() {
        return new HashSet<>(configRepository.findActiveLibreUserIdsWithCredentials());
    }

    @Override
    protected SyncResult run(UUID userId) {
        return syncService.syncUser(userId, false);
    }

    /**
     * Records an on-demand (sync-now) result: next poll at least 300 s out on the user's grid
     * (a 429 also pauses the lane for 60 s, and the key is the later of the two). DB writes happen
     * outside the lane lock; the heap key is only moved when the user is not in flight.
     */
    public void onForcedSync(UUID userId, SyncResult result) {
        if (result == null) {
            return;
        }
        Instant now = clock.instant();
        Instant key = SyncSlot.slotAtLeast300After(userId, now);
        switch (result.outcome()) {
            case NEW_DATA -> state.markNewData(userId,
                    result.newestTimestamp() == null ? 0L : result.newestTimestamp(), toLocal(now), toLocal(key));
            case NO_CHANGE -> state.markNoChange(userId, toLocal(now), toLocal(key));
            case ERROR, UNAVAILABLE -> state.markError(userId, toLocal(now), toLocal(key));
            case RATE_LIMITED -> {
                Instant pause = extendPause(now.plus(RATE_LIMIT_PAUSE));
                if (pause.isAfter(key)) {
                    key = pause;
                }
                state.markError(userId, toLocal(now), toLocal(key));
            }
            default -> {
                return;
            }
        }
        rekeyIfIdle(userId, key);
    }

    @Override
    protected Instant onResult(UUID id, Instant dueAt, SyncResult r, Instant now) {
        switch (r.outcome()) {
            case UNAVAILABLE -> {
                Instant retry = r.retryAt() != null ? r.retryAt() : now.plus(CIRCUIT_DEFAULT_RETRY);
                state.markError(id, toLocal(now), toLocal(retry));
                return retry;
            }
            case RATE_LIMITED -> {
                Instant pause = extendPause(now.plus(RATE_LIMIT_PAUSE));
                state.markError(id, toLocal(now), toLocal(pause));
                return pause;
            }
            default -> {
                return super.onResult(id, dueAt, r, now);
            }
        }
    }
}
