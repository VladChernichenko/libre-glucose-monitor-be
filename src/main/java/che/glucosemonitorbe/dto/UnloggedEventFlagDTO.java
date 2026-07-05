package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.UnloggedEventFlag;

import java.time.LocalDateTime;
import java.util.UUID;

/** API view of an {@link UnloggedEventFlag}. */
public record UnloggedEventFlagDTO(
        UUID id,
        String category,
        String direction,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        Double meanResidualMmol,
        Double sigmaMultiple,
        String state,
        LocalDateTime detectedAt,
        LocalDateTime resolvedAt) {

    public static UnloggedEventFlagDTO from(UnloggedEventFlag f) {
        return new UnloggedEventFlagDTO(
                f.getId(),
                f.getCategory().name(),
                f.getDirection().name(),
                f.getWindowStart(),
                f.getWindowEnd(),
                f.getMeanResidualMmol(),
                f.getSigmaMultiple(),
                f.getState().name(),
                f.getDetectedAt(),
                f.getResolvedAt());
    }
}
