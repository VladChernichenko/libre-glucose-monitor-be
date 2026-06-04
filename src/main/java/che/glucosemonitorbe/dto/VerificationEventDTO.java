package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.VerificationEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationEventDTO {
    private UUID id;
    private UUID noteId;
    private VerificationEvent.Status status;
    private Double baselineGlucose;
    private Double actualGlucose2h;
    private Double predictedDelta;
    private Double actualDelta;
    private Double error;
    private Double relativeErrorPct;
    private String skipReason;
    private LocalDateTime evaluatedAt;
    private LocalDateTime createdAt;
}
