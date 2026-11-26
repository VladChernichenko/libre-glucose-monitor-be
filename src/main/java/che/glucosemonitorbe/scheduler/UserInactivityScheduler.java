package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.service.UserActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically marks idle users inactive so the sync lanes stop polling their CGM accounts. Runs
 * only where the sync lanes run (single instance), since only they consume the flag.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.glucose-sync.enabled", havingValue = "true", matchIfMissing = true)
public class UserInactivityScheduler {

    private final UserActivityService userActivityService;

    @Scheduled(
            fixedDelayString = "${app.user-activity.check-interval-ms:300000}",
            initialDelayString = "${app.user-activity.check-interval-ms:300000}"
    )
    public void deactivateIdleUsers() {
        try {
            int n = userActivityService.deactivateIdleUsers();
            if (n > 0) {
                log.info("Marked {} idle user(s) inactive", n);
            }
        } catch (RuntimeException e) {
            log.warn("Idle-user check failed: {}", e.toString());
        }
    }
}
