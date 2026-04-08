package che.glucosemonitorbe.service.nutrition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NutritionSnapshot {
    private String absorptionMode; // DEFAULT_DECAY | GI_GL_ENHANCED
    private String source; // SPOONACULAR | MANUAL_CARBS
    private Double confidence;
    private Double totalCarbs;
    private Double fiber;
    private Double protein;
    private Double fat;
    private Double estimatedGi;
    private Double glycemicLoad;
    private String absorptionSpeedClass; // FAST | MEDIUM | SLOW | DEFAULT
    private List<String> normalizedFoods;
}
