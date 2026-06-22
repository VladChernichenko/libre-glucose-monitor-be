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
public class ActiveInsulinResponse {
    private LocalDateTime timestamp;
    private Double remainingUnits;
    private Double percentageRemaining;
    private Boolean isPeak;
}
