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
