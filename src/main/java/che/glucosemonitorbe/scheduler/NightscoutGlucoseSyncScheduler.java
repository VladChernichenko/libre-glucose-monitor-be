package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.NightscoutChartDataService;
import che.glucosemonitorbe.service.UserGlucoseSyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Periodically pulls Nightscout entries for each user with an active Nightscout config and merges
 * them into stored chart data (duplicates are ignored by {@link NightscoutChartDataService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.glucose-sync.enabled", havingValue = "true", matchIfMissing = true)
public class NightscoutGlucoseSyncScheduler {

    private final UserDataSourceConfigRepository configRepository;
    private final NightScoutIntegration nightScoutIntegration;
    private final NightscoutChartDataService nightscoutChartDataService;
    private final UserGlucoseSyncStateService syncStateService;

    @Value("${app.glucose-sync.entry-count:100}")
    private int entryCount;

    @Value("${app.glucose-sync.fast-interval-minutes:5}")
    private long fastIntervalMinutes;

    @Value("${app.glucose-sync.slow-interval-minutes:60}")
    private long slowIntervalMinutes;

    @Scheduled(
            initialDelayString = "${app.glucose-sync.initial-delay-ms:15000}",
            fixedDelayString = "${app.glucose-sync.fixed-delay-ms:300000}"
    )
    public void syncNightscoutForAllUsers() {
        LocalDateTime now = LocalDateTime.now();
        List<UUID> userIds = configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.NIGHTSCOUT);
        if (userIds.isEmpty()) {
            log.trace("Glucose sync: no users with active Nightscout configuration");
            return;
        }
        log.debug("Glucose sync: {} user(s) with active Nightscout", userIds.size());
        int skippedByBackoff = 0;
        int usersWithNewData = 0;
        int usersNoChange = 0;
        int usersErrored = 0;
        for (UUID userId : userIds) {
            try {
                UserGlucoseSyncState state = syncStateService.getOrCreate(userId);
                if (state.getNextPollAt() != null && now.isBefore(state.getNextPollAt())) {
                    skippedByBackoff++;
                    syncStateService.markSkippedBackoff(userId, now);
                    continue;
                }

                List<NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntries(userId, entryCount);
                nightscoutChartDataService.storeChartData(userId, entries);

                OptionalLong newestTs = entries.stream()
                        .map(NightscoutEntryDto::getDate)
                        .filter(d -> d != null)
                        .mapToLong(Long::longValue)
                        .max();

                long previousSeen = state.getLastSeenEntryTimestamp() == null ? Long.MIN_VALUE : state.getLastSeenEntryTimestamp();
                boolean hasNewData = newestTs.isPresent() && newestTs.getAsLong() > previousSeen;

                if (hasNewData) {
                    usersWithNewData++;
                    syncStateService.markNewData(
                            userId,
                            newestTs.getAsLong(),
                            now,
                            now.plusMinutes(fastIntervalMinutes)
                    );
                } else {
                    usersNoChange++;
                    syncStateService.markNoChange(
                            userId,
                            now,
                            now.plusMinutes(slowIntervalMinutes)
                    );
                }
            } catch (Exception e) {
                usersErrored++;
                syncStateService.markError(userId, now);
                log.warn("Glucose sync failed for user {}: {}", userId, e.getMessage());
            }
        }
        log.info(
                "Glucose sync summary: users={}, skippedByBackoff={}, newData={}, noChange={}, errors={}",
                userIds.size(),
                skippedByBackoff,
                usersWithNewData,
                usersNoChange,
                usersErrored
        );
    }
}
