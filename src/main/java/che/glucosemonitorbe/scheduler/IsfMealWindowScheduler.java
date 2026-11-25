package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.CarbRatioMealWindowProfileService;
import che.glucosemonitorbe.service.IsfMealWindowProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recomputes the meal-window ISF profile and the meal-window carb-ratio profile for every user once
 * per day at 02:00 UTC, when traffic is lowest. There is no on-bolus refresh: besides this job, only
 * {@code POST /api/isf/meal-windows/recompute} recomputes them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IsfMealWindowScheduler {

    private final UserRepository userRepository;
    private final IsfMealWindowProfileService isfProfileService;
    private final CarbRatioMealWindowProfileService carbRatioProfileService;

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void recomputeAllUsers() {
        log.info("IsfMealWindowScheduler: starting daily recompute");
        int success = 0;
        int failed = 0;
        for (User user : userRepository.findAll()) {
            try {
                isfProfileService.recomputeForUser(user.getId());
                carbRatioProfileService.recomputeForUser(user.getId());
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("IsfMealWindowScheduler: recompute failed user={} reason={}",
                        user.getId(), e.getMessage());
            }
        }
        log.info("IsfMealWindowScheduler: done success={} failed={}", success, failed);
    }
}
