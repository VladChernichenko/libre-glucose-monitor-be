package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.LibreAuthRequest;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.LibreLinkUpService;
import che.glucosemonitorbe.service.NightscoutChartDataService;
import che.glucosemonitorbe.service.UserGlucoseSyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Periodically fetches CGM readings from LibreLinkUp for each user with an active LIBRE_LINK_UP
 * configuration and stores them in {@code nightscout_chart_data} (reusing the same table/service
 * as the Nightscout scheduler so the iOS chart endpoint works for both data sources).
 *
 * <p>The scheduler re-authenticates per-user on each tick using credentials stored in
 * {@code user_data_source_config} (populated by {@link LibreLinkUpController} after a successful
 * iOS "Sync LibreLinkUp" action). The in-memory LLU token is reused if still valid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.glucose-sync.enabled", havingValue = "true", matchIfMissing = true)
public class LibreLinkUpGlucoseSyncScheduler {

    private final UserDataSourceConfigRepository configRepository;
    private final LibreLinkUpService libreLinkUpService;
    private final NightscoutChartDataService nightscoutChartDataService;
    private final UserGlucoseSyncStateService syncStateService;

    @Value("${app.libre-sync.fast-interval-minutes:5}")
    private long fastIntervalMinutes;

    @Value("${app.libre-sync.slow-interval-minutes:15}")
    private long slowIntervalMinutes;

    /** Direction string from LibreLinkUp trend int (mirrors iOS NightscoutEntry.directionArrow). */
    private static String trendToDirection(int trend) {
        switch (trend) {
            case 1: return "SingleUp";
            case 2: return "FortyFiveUp";
            case 3: return "Flat";
            case 4: return "FortyFiveDown";
            case 5: return "SingleDown";
            default: return "Flat";
        }
    }

    @Scheduled(
            initialDelayString  = "${app.libre-sync.initial-delay-ms:20000}",
            fixedDelayString    = "${app.libre-sync.fixed-delay-ms:300000}"
    )
    public void syncLibreForAllUsers() {
        LocalDateTime now = LocalDateTime.now();
        log.info("LibreLinkUp sync tick started at {}", now);

        List<UUID> userIds = configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP);

        if (userIds.isEmpty()) {
            log.info("LibreLinkUp sync tick finished: no users with active LibreLinkUp configuration");
            return;
        }
        log.info("LibreLinkUp sync: {} user(s) with active LibreLinkUp config", userIds.size());

        int skippedBackoff = 0, skippedNoCreds = 0, newData = 0, noChange = 0, errors = 0;

        for (UUID userId : userIds) {
            try {
                UserGlucoseSyncState state = syncStateService.getOrCreate(userId);
                if (state.getNextPollAt() != null && now.isBefore(state.getNextPollAt())) {
                    skippedBackoff++;
                    syncStateService.markSkippedBackoff(userId, now);
                    log.debug("LibreLinkUp sync user={} skipped by backoff (nextPollAt={})",
                            userId, state.getNextPollAt());
                    continue;
                }

                // Load stored LLU credentials.
                UserDataSourceConfig cfg = configRepository
                        .findByUserIdAndDataSourceAndIsActiveTrue(userId, UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)
                        .orElse(null);
                if (cfg == null || cfg.getLibreEmail() == null || cfg.getLibrePassword() == null
                        || cfg.getLibrePatientId() == null || cfg.getLibrePatientId().isBlank()) {
                    skippedNoCreds++;
                    log.warn("LibreLinkUp sync user={} skipped: missing credentials or patientId in config", userId);
                    continue;
                }

                // Ensure authenticated — re-authenticate if token missing (e.g. after restart).
                if (!libreLinkUpService.isAuthenticated(userId)) {
                    log.info("LibreLinkUp sync user={} re-authenticating (no in-memory token)", userId);
                    LibreAuthRequest authReq = new LibreAuthRequest();
                    authReq.setEmail(cfg.getLibreEmail());
                    authReq.setPassword(cfg.getLibrePassword());
                    authReq.setLocale(cfg.getLibreLocale());
                    libreLinkUpService.authenticate(authReq, userId);
                }

                // Fetch glucose readings (1 day window — sufficient for CGM chart).
                var glucoseData = libreLinkUpService.getGlucoseData(cfg.getLibrePatientId(), 1, userId);
                if (glucoseData == null || glucoseData.getData() == null || glucoseData.getData().isEmpty()) {
                    noChange++;
                    syncStateService.markNoChange(userId, now, now.plusMinutes(slowIntervalMinutes));
                    log.info("LibreLinkUp sync user={} no readings returned", userId);
                    continue;
                }

                // Convert LibreGlucoseReading → NightscoutEntryDto so we can reuse chart storage.
                List<NightscoutEntryDto> entries = new ArrayList<>();
                for (var reading : glucoseData.getData()) {
                    if (reading.getTimestamp() == null) continue;
                    long epochMs = reading.getTimestamp().getTime();
                    // Convert mmol/L back to mg/dL for storage (NightscoutEntryDto uses mg/dL).
                    int sgv = (int) Math.round(reading.getValue() * 18.0);
                    String direction = trendToDirection(reading.getTrend());
                    String id = "llu-" + cfg.getLibrePatientId().replace("-", "") + "-" + epochMs;
                    NightscoutEntryDto entry = new NightscoutEntryDto(
                            id, sgv, epochMs,
                            Instant.ofEpochMilli(epochMs).toString(),
                            reading.getTrend(), direction,
                            "LibreLinkUp", "sgv", 0,
                            Instant.ofEpochMilli(epochMs).toString()
                    );
                    entries.add(entry);
                }

                nightscoutChartDataService.storeChartData(userId, entries);

                OptionalLong newestTs = entries.stream()
                        .mapToLong(NightscoutEntryDto::getDate)
                        .max();

                long prevSeen = state.getLastSeenEntryTimestamp() == null ? Long.MIN_VALUE
                        : state.getLastSeenEntryTimestamp();
                boolean hasNew = newestTs.isPresent() && newestTs.getAsLong() > prevSeen;

                if (hasNew) {
                    newData++;
                    syncStateService.markNewData(userId, newestTs.getAsLong(), now,
                            now.plusMinutes(fastIntervalMinutes));
                    log.info("LibreLinkUp sync user={} new data (readings={}, newestTs={}, nextPoll={})",
                            userId, entries.size(), newestTs.getAsLong(),
                            now.plusMinutes(fastIntervalMinutes));
                } else {
                    noChange++;
                    syncStateService.markNoChange(userId, now, now.plusMinutes(slowIntervalMinutes));
                    log.info("LibreLinkUp sync user={} no new data (readings={}, nextPoll={})",
                            userId, entries.size(), now.plusMinutes(slowIntervalMinutes));
                }

            } catch (Exception e) {
                errors++;
                // If the LLU API rejected the token (401 / "not authenticated"), evict it from the
                // in-memory store so the next tick re-authenticates cleanly.  Without this the
                // scheduler would keep retrying with an expired bearer token forever.
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("401") || msg.toLowerCase().contains("unauthorized")
                        || msg.toLowerCase().contains("not authenticated")) {
                    libreLinkUpService.logout(userId);
                    log.info("LibreLinkUp sync user={} — token rejected (401), evicted for re-auth on next tick", userId);
                }
                syncStateService.markError(userId, now);
                log.warn("LibreLinkUp sync failed for user {}: {}", userId, e.getMessage());
            }
        }

        log.info("LibreLinkUp sync summary: users={}, skippedBackoff={}, skippedNoCreds={}, newData={}, noChange={}, errors={}",
                userIds.size(), skippedBackoff, skippedNoCreds, newData, noChange, errors);
    }
}
