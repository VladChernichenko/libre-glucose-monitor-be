package che.glucosemonitorbe.sync;

import che.glucosemonitorbe.domain.UserDataSourceConfig.DataSourceType;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.NightscoutSyncService;
import che.glucosemonitorbe.service.UserGlucoseSyncStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Nightscout due-queue lane: active Nightscout users minus active Libre users. Every outcome
 * (errors and rate limits included) uses the following slot; this lane never pauses.
 */
@Component
public class NightscoutSyncLane extends SyncLane {

    private final UserDataSourceConfigRepository configRepository;
    private final NightscoutSyncService syncService;

    @Autowired
    public NightscoutSyncLane(UserDataSourceConfigRepository configRepository,
                              NightscoutSyncService syncService,
                              UserGlucoseSyncStateService state) {
        this(configRepository, syncService, state, Clock.systemUTC());
    }

    NightscoutSyncLane(UserDataSourceConfigRepository configRepository,
                       NightscoutSyncService syncService,
                       UserGlucoseSyncStateService state,
                       Clock clock) {
        super("Nightscout", clock, state);
        this.configRepository = configRepository;
        this.syncService = syncService;
    }

    @Override
    protected Set<UUID> members() {
        Set<UUID> ids = new HashSet<>(configRepository
                .findDistinctUserIdsByDataSourceAndIsActiveTrue(DataSourceType.NIGHTSCOUT));
        ids.removeAll(configRepository
                .findDistinctUserIdsByDataSourceAndIsActiveTrue(DataSourceType.LIBRE_LINK_UP));
        return ids;
    }

    @Override
    protected SyncResult run(UUID userId) {
        return syncService.syncUser(userId);
    }
}
