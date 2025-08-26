package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsulinCalculationRequest {
    private Double carbs;
    private Double currentGlucose;
    private Double targetGlucose;
    private Double activeInsulin;
    private String mealType;
    private String userId;
}
