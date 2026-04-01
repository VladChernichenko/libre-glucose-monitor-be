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
public class PredictionPointDTO {
    private LocalDateTime timestamp;
    private Double predictedGlucose;
}
