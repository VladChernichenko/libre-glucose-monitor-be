package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.sync.SyncResult;
import che.glucosemonitorbe.sync.SyncOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Fetches and stores one user's Nightscout entries and reports what happened.
 * Writes no sync state: the caller decides {@code next_poll_at}.
 */
@Slf4j
@Service
public class NightscoutSyncService {

    private final NightScoutIntegration nightScoutIntegration;
    private final CgmReadingService cgmReadingService;
    private final UserGlucoseSyncStateService syncStateService;
    private final int entryCount;

    public NightscoutSyncService(NightScoutIntegration nightScoutIntegration,
                                 CgmReadingService cgmReadingService,
                                 UserGlucoseSyncStateService syncStateService,
                                 @Value("${app.glucose-sync.entry-count:100}") int entryCount) {
        this.nightScoutIntegration = nightScoutIntegration;
        this.cgmReadingService = cgmReadingService;
        this.syncStateService = syncStateService;
        this.entryCount = entryCount;
    }

    public SyncResult syncUser(UUID userId) {
        try {
            UserGlucoseSyncState state = syncStateService.getOrCreate(userId);

            List<NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntries(userId, entryCount);
            cgmReadingService.storeChartData(userId, entries, CgmReading.DataSource.NIGHTSCOUT);

            OptionalLong newestTs = entries.stream()
                    .map(NightscoutEntryDto::getDate)
                    .filter(d -> d != null)
                    .mapToLong(Long::longValue)
                    .max();

            long previousSeen = state.getLastSeenEntryTimestamp() == null
                    ? Long.MIN_VALUE : state.getLastSeenEntryTimestamp();
            if (newestTs.isPresent() && newestTs.getAsLong() > previousSeen) {
                log.info("Glucose sync user={} new data (entries={}, newestTs={})",
                        userId, entries.size(), newestTs.getAsLong());
                return SyncResult.newData(newestTs.getAsLong());
            }
            log.info("Glucose sync user={} no new data (entries={})", userId, entries.size());
            return SyncResult.of(SyncOutcome.NO_CHANGE);
        } catch (Exception e) {
            log.warn("Glucose sync failed for user {}: {}", userId, e.getMessage());
            return SyncResult.of(SyncOutcome.ERROR);
        }
    }
}
