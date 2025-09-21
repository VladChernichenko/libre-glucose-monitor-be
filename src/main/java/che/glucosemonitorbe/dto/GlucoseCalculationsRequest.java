package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlucoseCalculationsRequest {
    @NotNull(message = "Current glucose value is required")
    @Min(value = 0, message = "Glucose value must be positive")
    private Double currentGlucose;
    
    private String userId;
    private Boolean includePredictionFactors;
    private Integer predictionHorizonMinutes;
}

