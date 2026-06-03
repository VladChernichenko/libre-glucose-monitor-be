package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import che.glucosemonitorbe.service.LibreLinkUpSyncService.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodically triggers a LibreLinkUp sync for every user with an active LIBRE_LINK_UP
 * configuration. The actual per-user fetch+store (and all concurrency control) lives in
 * {@link LibreLinkUpSyncService}; this class only fans out across users and aggregates the result.
 *
 * <p>On-demand refreshes from the iOS app go through the same {@link LibreLinkUpSyncService} via
 * {@code POST /api/libre/sync-now}, so a manual refresh and a scheduler tick can never double-fetch
 * the same user concurrently.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.glucose-sync.enabled", havingValue = "true", matchIfMissing = true)
public class LibreLinkUpGlucoseSyncScheduler {

    private final UserDataSourceConfigRepository configRepository;
    private final LibreLinkUpSyncService syncService;

    /**
     * BE-P1-1: bounded thread pool so N users are synced in parallel (max 8 at once) instead of
     * sequentially. Prevents tick duration from scaling linearly with user count.
     */
    private static final int MAX_CONCURRENT_SYNCS = 8;
    private final ExecutorService syncExecutor = Executors.newFixedThreadPool(
            MAX_CONCURRENT_SYNCS,
            r -> { Thread t = new Thread(r, "libre-sync"); t.setDaemon(true); return t; }
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

        AtomicInteger newData = new AtomicInteger();
        AtomicInteger noChange = new AtomicInteger();
        AtomicInteger skippedBackoff = new AtomicInteger();
        AtomicInteger skippedNoCreds = new AtomicInteger();
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        List<CompletableFuture<Void>> futures = new ArrayList<>(userIds.size());
        for (UUID userId : userIds) {
            futures.add(CompletableFuture.runAsync(() -> {
                Outcome outcome = syncService.syncUser(userId, false);
                switch (outcome) {
                    case NEW_DATA         -> newData.incrementAndGet();
                    case NO_CHANGE        -> noChange.incrementAndGet();
                    case SKIPPED_BACKOFF  -> skippedBackoff.incrementAndGet();
                    case SKIPPED_NO_CREDS -> skippedNoCreds.incrementAndGet();
                    case IN_PROGRESS      -> inProgress.incrementAndGet();
                    case ERROR            -> errors.incrementAndGet();
                }
            }, syncExecutor));
        }

        // Wait for all user syncs to finish (4-min safety timeout to stay within fixedDelay).
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(4, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("LibreLinkUp sync: not all user tasks finished within timeout: {}", e.getMessage());
        }

        log.info("LibreLinkUp sync summary: users={}, newData={}, noChange={}, skippedBackoff={}, "
                        + "skippedNoCreds={}, inProgress={}, errors={}",
                userIds.size(), newData.get(), noChange.get(), skippedBackoff.get(),
                skippedNoCreds.get(), inProgress.get(), errors.get());
    }
}
