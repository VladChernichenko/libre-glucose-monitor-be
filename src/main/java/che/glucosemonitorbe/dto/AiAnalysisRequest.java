package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AiAnalysisRequest {
    @Min(1)
    @Max(72)
    private Integer windowHours = 12;
}
