package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.service.UnloggedEventDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans every real user's recent CGM window for unexplained residuals (probable unlogged
 * or under-estimated food/insulin). The heavy lifting and the feature-flag / seed-exclusion checks
 * live in {@link UnloggedEventDetectionService#scanAllRealUsers()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnloggedEventScanScheduler {

    private final UnloggedEventDetectionService detectionService;

    @Scheduled(
            initialDelayString = "${app.unlogged-events.initial-delay-ms:60000}",
            fixedDelayString = "${app.unlogged-events.scan-interval-ms:1200000}")
    public void scan() {
        log.debug("UnloggedEventScanScheduler: starting scan");
        try {
            detectionService.scanAllRealUsers();
        } catch (Exception e) {
            log.error("UnloggedEventScanScheduler failed: {}", e.getMessage(), e);
        }
    }
}
