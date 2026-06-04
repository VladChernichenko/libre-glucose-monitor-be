package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationSummaryDTO {
    private int nEvents;
    private Double meanError;
    private Double consistencyScore;
    private Double suggestedIsf;
    private Double suggestedCarbRatio;
    private boolean suggestionReady;
    /** LOW | MEDIUM | HIGH */
    private String confidence;
    private LocalDateTime lastUpdated;
}
