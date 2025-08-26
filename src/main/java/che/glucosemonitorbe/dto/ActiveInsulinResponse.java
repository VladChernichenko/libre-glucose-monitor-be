package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveInsulinResponse {
    private LocalDateTime timestamp;
    private Double remainingUnits;
    private Double percentageRemaining;
    private Boolean isPeak;
}
