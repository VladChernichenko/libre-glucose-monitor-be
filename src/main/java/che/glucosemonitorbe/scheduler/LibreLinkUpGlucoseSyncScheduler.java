package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import che.glucosemonitorbe.service.LibreLinkUpSyncService.Outcome;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

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

    /** Safety budget for a whole tick, so a slow batch can't run past the next scheduled tick. */
    @Value("${app.libre-sync.sync-timeout-ms:240000}")
    private long syncTimeoutMs;

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

        List<CompletableFuture<Outcome>> futures = new ArrayList<>(userIds.size());
        for (UUID userId : userIds) {
            futures.add(CompletableFuture.supplyAsync(() -> syncService.syncUser(userId, false), syncExecutor));
        }

        SyncTally tally = awaitAndTally(futures, userIds.size());

        log.info("LibreLinkUp sync summary: users={}, completed={}, newData={}, noChange={}, skippedBackoff={}, "
                        + "skippedNoCreds={}, inProgress={}, errors={}",
                userIds.size(), tally.completed(),
                tally.get(Outcome.NEW_DATA), tally.get(Outcome.NO_CHANGE), tally.get(Outcome.SKIPPED_BACKOFF),
                tally.get(Outcome.SKIPPED_NO_CREDS), tally.get(Outcome.IN_PROGRESS), tally.get(Outcome.ERROR));
    }

    /**
     * Waits for {@code futures} up to {@code syncTimeoutMs}, then tallies only the ones that
     * completed normally - futures still running at the deadline are excluded from the tally and
     * cancelled (best-effort: this does not interrupt the underlying sync, which is bounded by
     * RestTemplate's own connect/read timeouts, but it stops them from racing the result computed
     * here).
     */
    SyncTally awaitAndTally(List<CompletableFuture<Outcome>> futures, int totalUsers) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(syncTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            long pending = futures.stream().filter(f -> !f.isDone()).count();
            log.warn("LibreLinkUp sync: tick exceeded {}ms budget with {} of {} user sync(s) still in "
                            + "progress; those will be excluded from this tick's summary",
                    syncTimeoutMs, pending, totalUsers);
            futures.stream().filter(f -> !f.isDone()).forEach(f -> f.cancel(true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LibreLinkUp sync: interrupted while waiting for user sync tasks", e);
        } catch (ExecutionException e) {
            log.warn("LibreLinkUp sync: unexpected error in a user sync task", e.getCause());
        }

        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        long completed = 0;
        for (CompletableFuture<Outcome> future : futures) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                counts.merge(future.getNow(null), 1L, Long::sum);
                completed++;
            }
        }
        return new SyncTally(completed, counts);
    }

    /** Per-tick outcome tally. {@link #counts} only includes futures that completed normally. */
    record SyncTally(long completed, Map<Outcome, Long> counts) {
        long get(Outcome outcome) {
            return counts.getOrDefault(outcome, 0L);
        }
    }
}
