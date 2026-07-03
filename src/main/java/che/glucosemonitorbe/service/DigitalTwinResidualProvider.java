package che.glucosemonitorbe.service;

import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Spring bean that feeds each user's active {@link che.glucosemonitorbe.hovorka.learning.ResidualBiasModel}
 * into the live prediction path. Injected into
 * {@link che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService} so the learned time-of-day
 * corrections are auto-applied to predictions.
 */
@Primary
@Component
@RequiredArgsConstructor
public class DigitalTwinResidualProvider implements PredictionResidualProvider {

    private final DigitalTwinService digitalTwinService;

    @Override
    public double residualMmol(UUID userId, LocalDateTime pointTime) {
        if (userId == null || pointTime == null) return 0.0;
        return digitalTwinService.activeResidual(userId).correctionAt(pointTime.getHour());
    }

    @Override
    public double uncertaintySdMmol(UUID userId, int horizonMin) {
        return digitalTwinService.activeUncertainty(userId).sdAtHorizon(horizonMin);
    }
}
