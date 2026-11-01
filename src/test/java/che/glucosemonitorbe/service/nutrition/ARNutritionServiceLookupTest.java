package che.glucosemonitorbe.service.nutrition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.dto.ARFoodItem;
import che.glucosemonitorbe.dto.ARNutritionRequest;
import che.glucosemonitorbe.entity.OFFProductDocument;
import che.glucosemonitorbe.repository.OFFProductRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression for the chat card "60 g porridge and milk = 102.3 g carbs": OFF substring search matched
 * "milk" to milk-chocolate biscuits and "porridge" to a product with no nutrition data.
 */
class ARNutritionServiceLookupTest {

    private final OFFProductRepository off = mock(OFFProductRepository.class);
    private final GlycemicPatternMatchingService patterns = mock(GlycemicPatternMatchingService.class);
    private final OpenFoodFactsService offGi = mock(OpenFoodFactsService.class);
    private ARNutritionService service;

    @BeforeEach
    void setUp() {
        when(patterns.enrich(any())).thenAnswer(i -> i.getArgument(0));
        when(offGi.toNutritionSnapshot(any(), anyDouble()))
                .thenReturn(NutritionSnapshot.builder().estimatedGi(45.0).build());
        service = new ARNutritionService(off, mock(NutritionEnrichmentService.class), patterns, offGi);
    }

    private static OFFProductDocument product(String name, Double carbs, Double fat) {
        OFFProductDocument d = new OFFProductDocument();
        d.setProductName(name);
        OFFProductDocument.Nutriments n = new OFFProductDocument.Nutriments();
        n.setCarbohydrates100g(carbs);
        n.setFat100g(fat);
        d.setNutriments(n);
        return d;
    }

    private static ARFoodItem item(String label, double grams) {
        ARFoodItem f = new ARFoodItem();
        f.setLabel(label);
        f.setMassG(grams);
        f.setSegmentationConfidence(0.9);
        return f;
    }

    private NutritionSnapshot analyze(ARFoodItem... items) {
        ARNutritionRequest req = new ARNutritionRequest();
        req.setFoods(List.of(items));
        return service.analyze(req);
    }

    @Test
    void milkAndDryOatsUseReferenceValuesNotOffProducts() {
        NutritionSnapshot n = analyze(item(ARNutritionService.DRY_OATS, 60), item("milk", 250));

        // 60 g dry oats ~39.6 g + 250 g milk 12 g, as in the twin's own log (≈48 g).
        assertEquals(51.6, n.getTotalCarbs(), 0.05);
        assertEquals(12.2, n.getFat(), 0.05);
        verifyNoInteractions(off);
    }

    @Test
    void milkChocolateIsNotMatchedAsMilk() {
        when(off.findByProductNameContaining(eq("milk chocolate"), any()))
                .thenReturn(List.of(product("Finest milk chocolate", 50.0, 34.0)));

        NutritionSnapshot n = analyze(item("milk chocolate", 100));

        assertEquals(50.0, n.getTotalCarbs(), 0.05);
    }

    @Test
    void offPrefersAProductWithDataAndTheClosestName() {
        OFFProductDocument noData = product("Oat and barley porridge", null, null);
        OFFProductDocument biscuit = product("Mcvitie's, mini gingerbread men, milk chocolate", 68.2, 20.0);
        OFFProductDocument closest = product("Buckwheat", 71.5, 3.4);
        OFFProductDocument longer = product("Organic roasted buckwheat groats", 70.0, 3.0);

        assertSame(closest, ARNutritionService.bestOffMatch("buckwheat", List.of(noData, longer, closest)));
        assertNull(ARNutritionService.bestOffMatch("porridge", List.of(noData)));
        assertSame(biscuit, ARNutritionService.bestOffMatch("gingerbread", List.of(biscuit)));
    }
}
