package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.service.VerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls every 15 minutes for PENDING verification events whose 2-hour window has elapsed,
 * fetches the actual CGM reading, computes the error, and refreshes the per-user summary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationScheduler {

    private final VerificationService verificationService;

    @Scheduled(fixedDelay = 15 * 60 * 1000)  // every 15 minutes
    public void evaluatePendingVerifications() {
        log.debug("VerificationScheduler: evaluating pending verification events");
        try {
            verificationService.evaluatePending();
        } catch (Exception e) {
            log.error("VerificationScheduler failed: {}", e.getMessage(), e);
        }
    }
}
