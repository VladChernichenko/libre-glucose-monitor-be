package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.NightscoutChartDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
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

    @Value("${app.glucose-sync.entry-count:100}")
    private int entryCount;

    @Scheduled(
            initialDelayString = "${app.glucose-sync.initial-delay-ms:15000}",
            fixedDelayString = "${app.glucose-sync.fixed-delay-ms:300000}"
    )
    public void syncNightscoutForAllUsers() {
        List<UUID> userIds = configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.NIGHTSCOUT);
        if (userIds.isEmpty()) {
            log.trace("Glucose sync: no users with active Nightscout configuration");
            return;
        }
        log.debug("Glucose sync: {} user(s) with active Nightscout", userIds.size());
        for (UUID userId : userIds) {
            try {
                List<NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntries(userId, entryCount);
                nightscoutChartDataService.storeChartData(userId, entries);
            } catch (Exception e) {
                log.warn("Glucose sync failed for user {}: {}", userId, e.getMessage());
            }
        }
    }
}
