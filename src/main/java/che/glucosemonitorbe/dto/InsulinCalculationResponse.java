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
public class InsulinCalculationResponse {
    private Double recommendedInsulin;
    private LocalDateTime calculationTime;
    private String message;
    private String status;
}
