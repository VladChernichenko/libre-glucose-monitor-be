package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ARNutritionRequest {
    @NotEmpty
    private List<ARFoodItem> foods;

    /** 15%-of-mass estimate from iOS — used only if every food label misses OFF */
    private Double fallbackCarbs;
}
