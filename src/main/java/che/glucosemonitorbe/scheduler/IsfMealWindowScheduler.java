package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.IsfMealWindowProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recomputes the ISF meal-window profile for every active user once per day at 02:00 UTC,
 * when traffic is lowest. On-bolus refresh is wired through {@link IsfMealWindowProfileService#recomputeForUser}
 * directly from the note-creation path (see {@code NotesService}) — not from here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IsfMealWindowScheduler {

    private final UserRepository userRepository;
    private final IsfMealWindowProfileService isfProfileService;

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void recomputeAllUsers() {
        log.info("IsfMealWindowScheduler: starting daily recompute");
        int success = 0;
        int failed = 0;
        for (User user : userRepository.findAll()) {
            try {
                isfProfileService.recomputeForUser(user.getId());
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
