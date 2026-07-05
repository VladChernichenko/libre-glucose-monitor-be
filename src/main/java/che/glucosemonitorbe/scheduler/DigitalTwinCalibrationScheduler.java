package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.service.DigitalTwinCalibrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly re-calibration of every real user's digital twin. Runs off-peak (03:15 local) since a full
 * per-user fit replays the ODE many times; the {@link DigitalTwinCalibrationService} itself is a
 * no-op when the {@code digital-twin-enabled} feature flag is off. Seed (AZT1D dataset) users are
 * excluded by {@link DigitalTwinCalibrationService#calibrateAllRealUsers()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigitalTwinCalibrationScheduler {

    private final DigitalTwinCalibrationService calibrationService;

    @Scheduled(cron = "${app.digital-twin.cron:0 15 3 * * *}")
    public void recalibrateAll() {
        log.debug("DigitalTwinCalibrationScheduler: starting nightly recalibration");
        try {
            calibrationService.calibrateAllRealUsers();
        } catch (Exception e) {
            log.error("DigitalTwinCalibrationScheduler failed: {}", e.getMessage(), e);
        }
    }
}
