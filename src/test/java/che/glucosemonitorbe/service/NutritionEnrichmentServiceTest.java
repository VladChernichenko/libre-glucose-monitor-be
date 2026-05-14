package che.glucosemonitorbe.service;

import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NutritionEnrichmentServiceTest {

    private final NutritionEnrichmentService service = new NutritionEnrichmentService();

    @Test
    void carbOnlyInputFallsBackToDefaultDecay() {
        NutritionSnapshot snapshot = service.enrichFromText("20g", "", 20.0);
        assertEquals("DEFAULT_DECAY", snapshot.getAbsorptionMode());
        assertEquals("MANUAL_CARBS", snapshot.getSource());
    }

    @Test
    void mealInputUsesEnhancedMode() {
        NutritionSnapshot snapshot = service.enrichFromText("rye bread and milk", "", 40.0);
        assertEquals("GI_GL_ENHANCED", snapshot.getAbsorptionMode());
        assertEquals("KEYWORD_GI", snapshot.getSource());
    }
}
