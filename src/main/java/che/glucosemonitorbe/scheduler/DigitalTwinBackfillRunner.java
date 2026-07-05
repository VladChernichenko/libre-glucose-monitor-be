package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.service.DigitalTwinCalibrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot backfill job: recalibrates every real (non-seed) user's digital twin with the current
 * calibrator (Levenberg–Marquardt + EGP₀) immediately, then exits. This is the on-demand trigger for
 * applying the twin to all users without waiting for the nightly {@link DigitalTwinCalibrationScheduler}.
 *
 * <p>Activated only under the {@code recalibrate-cli} Spring profile, so a normal application boot
 * never runs it. Run it as a one-off (it connects to the same database as the app):</p>
 *
 * <pre>{@code SPRING_PROFILES_ACTIVE=recalibrate-cli ./gradlew bootRun}</pre>
 *
 * <p>The paired {@code application-recalibrate-cli.yml} disables the web server so this job can run
 * alongside an already-running instance without competing for the HTTP port; that instance simply
 * picks up the refreshed twins from the database on its next prediction (cache TTL).</p>
 */
@Slf4j
@Component
@Profile("recalibrate-cli")
@RequiredArgsConstructor
public class DigitalTwinBackfillRunner implements ApplicationRunner {

    private final DigitalTwinCalibrationService calibrationService;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Digital-twin backfill starting — recalibrating all real users (LM + EGP0)…");
        DigitalTwinCalibrationService.BatchSummary summary = calibrationService.calibrateAllRealUsers();
        log.info("Digital-twin backfill complete: {}", summary);
        int exitCode = SpringApplication.exit(context, () -> summary.failed() == 0 ? 0 : 1);
        System.exit(exitCode);
    }
}
