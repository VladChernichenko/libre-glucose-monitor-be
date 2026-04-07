package che.glucosemonitorbe.service;

import che.glucosemonitorbe.service.nutrition.NutritionApiNinjaService;
import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NutritionEnrichmentServiceTest {

    @Test
    void carbOnlyInputFallsBackToDefaultDecay() {
        NutritionApiNinjaService api = mock(NutritionApiNinjaService.class);
        NutritionEnrichmentService service = new NutritionEnrichmentService(api);
        NutritionSnapshot snapshot = service.enrichFromText("20g", "", 20.0);
        assertEquals("DEFAULT_DECAY", snapshot.getAbsorptionMode());
        assertEquals("MANUAL_CARBS", snapshot.getSource());
    }

    @Test
    void mealInputUsesEnhancedModeWhenApiDataExists() {
        NutritionApiNinjaService api = mock(NutritionApiNinjaService.class);
        when(api.lookupFoods("rye bread and milk"))
                .thenReturn(List.of(
                        Map.of("name", "rye bread", "carbohydrates_total_g", 30.0, "fiber_g", 5.0, "protein_g", 6.0, "fat_total_g", 2.0),
                        Map.of("name", "milk", "carbohydrates_total_g", 10.0, "fiber_g", 0.0, "protein_g", 7.0, "fat_total_g", 7.0)
                ));
        NutritionEnrichmentService service = new NutritionEnrichmentService(api);
        NutritionSnapshot snapshot = service.enrichFromText("rye bread and milk", "", 0.0);
        assertEquals("GI_GL_ENHANCED", snapshot.getAbsorptionMode());
    }
}
