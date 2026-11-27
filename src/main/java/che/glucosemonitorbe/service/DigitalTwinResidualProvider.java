package che.glucosemonitorbe.service;

import che.glucosemonitorbe.hovorka.ActivityGains;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Spring bean that feeds each user's applied digital twin (ISF scale, activity gains, confidence
 * band) into the live prediction path of
 * {@link che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService}.
 */
@Primary
@Component
@RequiredArgsConstructor
public class DigitalTwinResidualProvider implements PredictionResidualProvider {

    private final DigitalTwinService digitalTwinService;

    @Override
    public double isfScale(UUID userId) {
        if (userId == null) return 1.0;
        return digitalTwinService.activeScales(userId)
                .map(s -> s.clamped().isfScale())
                .orElse(1.0);
    }

    @Override
    public ActivityGains activityGains(UUID userId) {
        if (userId == null) return ActivityGains.NEUTRAL;
        return digitalTwinService.activeActivityGains(userId).orElse(ActivityGains.NEUTRAL);
    }

    @Override
    public double uncertaintySdMmol(UUID userId, int horizonMin) {
        return digitalTwinService.activeUncertainty(userId).sdAtHorizon(horizonMin);
    }
}
