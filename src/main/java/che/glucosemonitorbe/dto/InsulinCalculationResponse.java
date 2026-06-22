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
public class InsulinCalculationResponse {
    private Double recommendedInsulin;
    private LocalDateTime calculationTime;
    private String message;
    private String status;
}
