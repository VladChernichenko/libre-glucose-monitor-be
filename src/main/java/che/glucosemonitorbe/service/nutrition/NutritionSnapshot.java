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
    private String source; // SPOONACULAR | MANUAL_CARBS | AR_OFF
    private Double confidence;
    private Double totalCarbs;
    private Double fiber;
    private Double protein;
    private Double fat;
    private Double estimatedGi;
    private Double glycemicLoad;
    private String absorptionSpeedClass; // FAST | MEDIUM | SLOW | DEFAULT
    private List<String> normalizedFoods;

    // Per-food breakdown from AR volume scan (null for non-AR sources)
    private List<FoodMassBreakdown> foodMassBreakdown;

    // Glycemic response pattern fields (populated after photo analysis)
    private String patternName;           // e.g. "Double Wave", "Fast Spike"
    private String bolusStrategy;         // Normal | Extended | Dual Wave
    private Double suggestedDurationHours; // expected glucose elevation window
    private Integer mealSequencingPriority; // 1=eat first, 3=eat last
    private String curveDescription;      // human-readable curve explanation
    private Integer preBolusPauseMinutes; // recommended minutes to wait after injection before eating

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FoodMassBreakdown {
        private String label;
        private double massG;
        private double carbs;
        private double fat;
        private double protein;
        private double fiber;
        private double gi;
        /** Matched OFF product name, or null if keyword fallback was used */
        private String offProductName;
        private double confidence;
    }
}
