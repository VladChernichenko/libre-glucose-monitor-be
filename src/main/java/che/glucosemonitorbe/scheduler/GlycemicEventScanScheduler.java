package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.service.GlycemicEventScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans every real user's recent CGM window for unexplained residuals (probable unlogged
 * or under-estimated food/insulin). The heavy lifting and the feature-flag / seed-exclusion checks
 * live in {@link GlycemicEventScanService#scanAllRealUsers()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlycemicEventScanScheduler {

    private final GlycemicEventScanService detectionService;

    @Scheduled(
            initialDelayString = "${app.glycemic-events.initial-delay-ms:60000}",
            fixedDelayString = "${app.glycemic-events.scan-interval-ms:1200000}")
    public void scan() {
        log.debug("GlycemicEventScanScheduler: starting scan");
        try {
            detectionService.scanAllRealUsers();
        } catch (Exception e) {
            log.error("GlycemicEventScanScheduler failed: {}", e.getMessage(), e);
        }
    }
}
