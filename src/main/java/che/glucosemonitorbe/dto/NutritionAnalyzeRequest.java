package che.glucosemonitorbe.dto;

import lombok.Data;

@Data
public class NutritionAnalyzeRequest {
    private String ingredientsText;
    private Double fallbackCarbs;
}
