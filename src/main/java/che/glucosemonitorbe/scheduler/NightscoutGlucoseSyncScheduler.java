package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.service.UserReactivatedEvent;
import che.glucosemonitorbe.sync.NightscoutSyncLane;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Drives the Nightscout due-queue lane ({@link NightscoutSyncLane}): reconciles membership at startup and every
 * 60 s, and pumps due users into a bounded pool every second. All per-user scheduling lives in the lane.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.glucose-sync.enabled", havingValue = "true", matchIfMissing = true)
public class NightscoutGlucoseSyncScheduler {

    private final NightscoutSyncLane lane;

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

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        lane.reconcile();
    }

    /** A returning user rejoins the lane and syncs now; off the request thread. */
    @EventListener
    public void onUserReactivated(UserReactivatedEvent event) {
        try {
            syncExecutor.execute(() -> lane.wake(event.userId()));
        } catch (RejectedExecutionException e) {
            // Shutting down; the next reconcile (if any) picks the user up.
        }
    }

    @Scheduled(
            fixedDelayString = "${app.glucose-sync.reconcile-interval-ms:60000}",
            initialDelayString = "${app.glucose-sync.reconcile-interval-ms:60000}"
    )
    public void reconcile() {
        lane.reconcile();
    }

    @Scheduled(fixedDelayString = "${app.glucose-sync.pump-interval-ms:1000}")
    public void pump() {
        lane.pump(syncExecutor);
    }
}
