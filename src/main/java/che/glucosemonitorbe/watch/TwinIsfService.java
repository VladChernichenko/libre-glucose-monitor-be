package che.glucosemonitorbe.watch;

import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.service.DigitalTwinService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The ISF a correction should be priced with: the settings value scaled by the applied digital
 * twin's learned insulin sensitivity, so the dose matches the sensitivity the forecast assumed.
 */
@Service
@RequiredArgsConstructor
public class TwinIsfService {

    private final DigitalTwinService digitalTwinService;

    /** @return the ISF to price with, or {@code NaN} when settings hold none (the calculator refuses it) */
    public double effectiveIsf(UUID userId, UserSettingsDTO settings, LocalDateTime time) {
        Double base = settings.getEffectiveIsf(time);
        if (base == null) return Double.NaN;
        double scale = digitalTwinService.activeScales(userId)
                .map(s -> s.clamped().isfScale())
                .orElse(1.0);
        return base * scale;
    }
}
