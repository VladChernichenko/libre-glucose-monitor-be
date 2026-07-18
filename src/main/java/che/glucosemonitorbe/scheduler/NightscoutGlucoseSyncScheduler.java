package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.CgmReadingService;
import che.glucosemonitorbe.service.UserGlucoseSyncStateService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodically pulls Nightscout entries for each user with an active Nightscout config and merges
 * them into the shared CGM cache (duplicates are ignored by {@link CgmReadingService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.glucose-sync.enabled", havingValue = "true", matchIfMissing = true)
public class NightscoutGlucoseSyncScheduler {

    private final UserDataSourceConfigRepository configRepository;
    private final NightScoutIntegration nightScoutIntegration;
    private final CgmReadingService cgmReadingService;
    private final UserGlucoseSyncStateService syncStateService;

    @Value("${app.glucose-sync.entry-count:100}")
    private int entryCount;

    @Value("${app.glucose-sync.fast-interval-minutes:5}")
    private long fastIntervalMinutes;

    @Value("${app.glucose-sync.slow-interval-minutes:60}")
    private long slowIntervalMinutes;

    /** BE-P1-1 fix: bounded parallel executor - same pattern as LibreLinkUpGlucoseSyncScheduler. */
    private static final int MAX_CONCURRENT_SYNCS = 8;
    private final ExecutorService syncExecutor = Executors.newFixedThreadPool(
            MAX_CONCURRENT_SYNCS,
            r -> { Thread t = new Thread(r, "ns-sync"); t.setDaemon(true); return t; }
    );

    @PreDestroy
    public void shutdownExecutor() {
        syncExecutor.shutdown();
        try {
            if (!syncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                syncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            syncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(
            initialDelayString = "${app.glucose-sync.initial-delay-ms:15000}",
            fixedDelayString = "${app.glucose-sync.fixed-delay-ms:300000}"
    )
    public void syncNightscoutForAllUsers() {
        LocalDateTime now = LocalDateTime.now();
        log.info("Glucose sync tick started at {}", now);
        List<UUID> userIds = configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.NIGHTSCOUT);
        if (userIds.isEmpty()) {
            log.info("Glucose sync tick finished: no users with active Nightscout configuration");
            return;
        }
        log.info("Glucose sync: {} user(s) with active Nightscout", userIds.size());
        // Users whose active data source is LibreLinkUp: do not sync Nightscout for them
        // (they have a dedicated LibreLinkUpGlucoseSyncScheduler).
        List<UUID> libreUserIds = configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP);
        java.util.Set<UUID> libreUserSet = new java.util.HashSet<>(libreUserIds);

        // BE-P1-1 fix: process users in parallel up to MAX_CONCURRENT_SYNCS at a time.
        AtomicInteger skippedByBackoff = new AtomicInteger();
        AtomicInteger skippedLibre = new AtomicInteger();
        AtomicInteger usersWithNewData = new AtomicInteger();
        AtomicInteger usersNoChange = new AtomicInteger();
        AtomicInteger usersErrored = new AtomicInteger();

        List<CompletableFuture<Void>> futures = new ArrayList<>(userIds.size());
        for (UUID userId : userIds) {
            futures.add(CompletableFuture.runAsync(() -> {
                if (libreUserSet.contains(userId)) {
                    skippedLibre.incrementAndGet();
                    log.debug("Glucose sync user={} skipped: active LibreLinkUp data source", userId);
                    return;
                }
                try {
                    UserGlucoseSyncState state = syncStateService.getOrCreate(userId);
                    if (state.getNextPollAt() != null && now.isBefore(state.getNextPollAt())) {
                        skippedByBackoff.incrementAndGet();
                        syncStateService.markSkippedBackoff(userId, now);
                        log.info("Glucose sync user={} skipped by backoff (nextPollAt={})", userId, state.getNextPollAt());
                        return;
                    }

                    List<NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntries(userId, entryCount);
                    cgmReadingService.storeChartData(userId, entries, CgmReading.DataSource.NIGHTSCOUT);

                    OptionalLong newestTs = entries.stream()
                            .map(NightscoutEntryDto::getDate)
                            .filter(d -> d != null)
                            .mapToLong(Long::longValue)
                            .max();

                    long previousSeen = state.getLastSeenEntryTimestamp() == null ? Long.MIN_VALUE : state.getLastSeenEntryTimestamp();
                    boolean hasNewData = newestTs.isPresent() && newestTs.getAsLong() > previousSeen;

                    if (hasNewData) {
                        usersWithNewData.incrementAndGet();
                        syncStateService.markNewData(userId, newestTs.getAsLong(), now, now.plusMinutes(fastIntervalMinutes));
                        log.info("Glucose sync user={} new data detected (entries={}, newestTs={}, nextPollAt={})",
                                userId, entries.size(), newestTs.getAsLong(), now.plusMinutes(fastIntervalMinutes));
                    } else {
                        usersNoChange.incrementAndGet();
                        syncStateService.markNoChange(userId, now, now.plusMinutes(slowIntervalMinutes));
                        log.info("Glucose sync user={} no new data (entries={}, nextPollAt={})",
                                userId, entries.size(), now.plusMinutes(slowIntervalMinutes));
                    }
                } catch (Exception e) {
                    usersErrored.incrementAndGet();
                    syncStateService.markError(userId, now);
                    log.warn("Glucose sync failed for user {}: {}", userId, e.getMessage());
                }
            }, syncExecutor));
        }

        // Wait for all user syncs to finish (with 4-min safety timeout to stay within fixedDelay).
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(4, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Glucose sync: not all user tasks finished within timeout: {}", e.getMessage());
        }

        log.info("Glucose sync summary: users={}, skippedLibre={}, skippedByBackoff={}, newData={}, noChange={}, errors={}",
                userIds.size(), skippedLibre.get(), skippedByBackoff.get(),
                usersWithNewData.get(), usersNoChange.get(), usersErrored.get());
    }
}
