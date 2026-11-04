package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.repository.GlycemicResponsePatternRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FoodCompositionServiceTest {

    @TempDir
    Path dir;

    private FoodCompositionService service() throws Exception {
        FoodCatalog catalog = new FoodCatalog(dir.resolve("food.sqlite"),
                new String(getClass().getResourceAsStream("/food/schema.sql").readAllBytes()),
                new String(getClass().getResourceAsStream("/food/seed.sql").readAllBytes()));
        GlycemicResponsePatternRepository repo = mock(GlycemicResponsePatternRepository.class);
        when(repo.findAllByOrderByMealSequencingPriorityAsc()).thenReturn(List.of());
        return new FoodCompositionService(catalog, new GlycemicPatternMatchingService(repo));
    }

    @Test
    void juicePortionUsesCatalogGiAndComputesLoad() throws Exception {
        NutritionSnapshot snap = service().compose(List.of(new FoodPortion("juice", 200)));
        assertEquals(50.0, snap.getTotalCarbs(), 0.1);
        assertEquals(70.0, snap.getEstimatedGi(), 0.1);
        assertEquals(35.0, snap.getGlycemicLoad(), 0.1);
        assertEquals(Boolean.FALSE, snap.getMassEstimated());
    }

    @Test
    void whiteBreadHasHigherGiThanPlainBread() throws Exception {
        FoodCompositionService svc = service();
        assertEquals(75.0, svc.compose(List.of(new FoodPortion("white bread", 100))).getEstimatedGi(), 0.1);
        assertEquals(70.0, svc.compose(List.of(new FoodPortion("bread", 100))).getEstimatedGi(), 0.1);
    }

    @Test
    void unknownFoodDoesNotInventAMeal() throws Exception {
        NutritionSnapshot snap = service().compose(List.of(new FoodPortion("zzzz-not-a-food", 100)));
        assertEquals(0.0, snap.getTotalCarbs(), 0.01);
        assertNull(snap.getEstimatedGi());
        assertEquals("UNMATCHED", snap.getSource());
    }

    @Test
    void eggHasNoGlycemicIndex() throws Exception {
        NutritionSnapshot snap = service().compose(List.of(new FoodPortion("egg", 60)));
        assertNull(snap.getEstimatedGi());
        assertNull(snap.getGlycemicLoad());
        assertTrue(snap.getProtein() > 0);
    }

    @Test
    void typedWeightScalesRiceAndLoggedCarbsWithoutWeightKeepTheLoggedTotal() throws Exception {
        FoodCompositionService svc = service();
        NutritionSnapshot weighed = svc.composeFromText("rice 150 g", "", null);
        assertEquals(42.0, weighed.getTotalCarbs(), 0.2);
        assertEquals(72.0, weighed.getEstimatedGi(), 0.1);

        NutritionSnapshot logged = svc.composeFromText("rice", "", 60.0);
        assertEquals(60.0, logged.getTotalCarbs(), 0.1);
        assertEquals(72.0, logged.getEstimatedGi(), 0.1);
    }

    @Test
    void carbGramsAloneDoNotInventAnIndex() throws Exception {
        NutritionSnapshot snap = service().composeFromText("20g", "", 20.0);
        assertEquals(20.0, snap.getTotalCarbs(), 0.01);
        assertNull(snap.getEstimatedGi());
        assertEquals("MANUAL_CARBS", snap.getSource());
    }

    @Test
    void repeatedRaffaelloPortionsUseTheLabelAndSumTheWeight() throws Exception {
        NutritionSnapshot snap = service().compose(List.of(
                new FoodPortion("Raffaello", 15, true),
                new FoodPortion("Raffaello", 15, true),
                new FoodPortion("Raffaello", 15, true)));
        assertEquals(17.4, snap.getTotalCarbs(), 0.1);
        assertEquals(21.7, snap.getFat(), 0.1);
        assertEquals(3.3, snap.getProtein(), 0.1);
        assertNull(snap.getEstimatedGi());
        assertEquals(List.of("Raffaello 45 g"), snap.getNormalizedFoods());
        assertEquals("FOOD_CATALOG", snap.getSource());
    }
}
