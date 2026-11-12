package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.dto.OFFProductDto;
import che.glucosemonitorbe.repository.GlycemicResponsePatternRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FoodCompositionServiceTest {

    @TempDir
    Path dir;

    private FoodCompositionService service() throws Exception {
        return service(null);
    }

    private FoodCompositionService service(OpenFoodFactsService off) throws Exception {
        FoodCatalog catalog = new FoodCatalog(dir.resolve("food.sqlite"),
                new String(getClass().getResourceAsStream("/food/schema.sql").readAllBytes()),
                new String(getClass().getResourceAsStream("/food/seed.sql").readAllBytes()));
        GlycemicResponsePatternRepository repo = mock(GlycemicResponsePatternRepository.class);
        when(repo.findAllByOrderByMealSequencingPriorityAsc()).thenReturn(List.of());
        return new FoodCompositionService(catalog, new GlycemicPatternMatchingService(repo), off);
    }

    private static OFFProductDto packagedBar() {
        OFFProductDto p = new OFFProductDto();
        p.setProductName("Protein Bar XYZ");
        p.setBarcode("3017620425035");
        p.setServingQuantityG(50.0);
        OFFProductDto.Nutriments n = new OFFProductDto.Nutriments();
        n.setCarbohydrates100g(40.0);
        n.setFiber100g(5.0);
        n.setProteins100g(20.0);
        n.setFat100g(10.0);
        n.setEnergyKcal100g(300.0);
        p.setNutriments(n);
        p.setCategoriesTags(List.of("en:protein-bars"));
        return p;
    }

    @Test
    void juicePortionUsesCatalogGiAndComputesLoad() throws Exception {
        NutritionSnapshot snap = service().compose(List.of(new FoodPortion("juice", 200)));
        // USDA orange juice ~10.4 g/100 g → 20.8 g in 200 g; GL = GI×(carbs−fiber)/100.
        assertEquals(20.8, snap.getTotalCarbs(), 0.1);
        assertEquals(50.0, snap.getEstimatedGi(), 0.1);
        assertEquals(10.3, snap.getGlycemicLoad(), 0.2);
        assertEquals(Boolean.FALSE, snap.getMassEstimated());
    }

    @Test
    void orangeJuiceAliasMatchesSameRow() throws Exception {
        NutritionSnapshot snap = service().compose(List.of(new FoodPortion("orange juice", 200)));
        assertEquals("FOOD_CATALOG", snap.getSource());
        assertEquals(20.8, snap.getTotalCarbs(), 0.1);
    }

    @Test
    void compoundVisionLabelUsesPrimaryCatalogFoodNotOff() throws Exception {
        OpenFoodFactsService off = mock(OpenFoodFactsService.class);
        when(off.searchProducts(anyString(), anyInt())).thenReturn(List.of(packagedBar()));
        when(off.toNutritionSnapshot(any(), anyDouble())).thenReturn(NutritionSnapshot.builder()
                .source("OPEN_FOOD_FACTS")
                .totalCarbs(400.0)
                .build());

        NutritionSnapshot snap = service(off).compose(List.of(
                new FoodPortion("barley with fried onions and meat", 250)));
        assertEquals("FOOD_CATALOG", snap.getSource());
        assertEquals(70.5, snap.getTotalCarbs(), 0.5); // 28.2 g/100 g × 2.5
        verify(off, never()).searchProducts(anyString(), anyInt());
    }

    @Test
    void pluralGrapesUsesCatalogNotOffJunk() throws Exception {
        OpenFoodFactsService off = mock(OpenFoodFactsService.class);
        // If plural matching fails, OFF would invent ~94 g/100 g (141 g for 150 g).
        when(off.searchProducts(anyString(), anyInt())).thenReturn(List.of(packagedBar()));
        when(off.toNutritionSnapshot(any(), anyDouble())).thenReturn(NutritionSnapshot.builder()
                .source("OPEN_FOOD_FACTS")
                .totalCarbs(141.2)
                .fiber(4.0)
                .protein(0.0)
                .fat(0.0)
                .estimatedGi(50.0)
                .build());

        NutritionSnapshot snap = service(off).compose(List.of(new FoodPortion("grapes", 150)));
        assertEquals("FOOD_CATALOG", snap.getSource());
        assertEquals(27.0, snap.getTotalCarbs(), 0.2); // 18 g/100 g × 1.5
        assertEquals(53.0, snap.getEstimatedGi(), 0.1);
        verify(off, never()).searchProducts(anyString(), anyInt());
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

    @Test
    void catalogHitDoesNotCallOpenFoodFacts() throws Exception {
        OpenFoodFactsService off = mock(OpenFoodFactsService.class);
        NutritionSnapshot snap = service(off).compose(List.of(new FoodPortion("apple", 100)));
        assertEquals("FOOD_CATALOG", snap.getSource());
        assertTrue(snap.getTotalCarbs() > 0);
        verify(off, never()).lookupByBarcode(anyString());
        verify(off, never()).searchProducts(anyString(), anyInt());
    }

    @Test
    void unknownFoodFallsBackToOffSearch() throws Exception {
        OpenFoodFactsService off = mock(OpenFoodFactsService.class);
        OFFProductDto product = packagedBar();
        when(off.searchProducts("Protein Bar XYZ", 5)).thenReturn(List.of(product));
        when(off.toNutritionSnapshot(eq(product), eq(50.0))).thenAnswer(inv -> {
            OFFProductDto p = inv.getArgument(0);
            double g = inv.getArgument(1);
            double scale = g / 100.0;
            return NutritionSnapshot.builder()
                    .source("OPEN_FOOD_FACTS")
                    .absorptionMode("GI_GL_ENHANCED")
                    .confidence(0.9)
                    .totalCarbs(p.getNutriments().getCarbohydrates100g() * scale)
                    .fiber(p.getNutriments().getFiber100g() * scale)
                    .protein(p.getNutriments().getProteins100g() * scale)
                    .fat(p.getNutriments().getFat100g() * scale)
                    .estimatedGi(55.0)
                    .glycemicLoad(17.5)
                    .normalizedFoods(List.of(p.getProductName()))
                    .build();
        });

        NutritionSnapshot snap = service(off).compose(List.of(new FoodPortion("Protein Bar XYZ", 50)));
        assertEquals("OPEN_FOOD_FACTS", snap.getSource());
        assertEquals(20.0, snap.getTotalCarbs(), 0.1);
        assertEquals(10.0, snap.getProtein(), 0.1);
        assertEquals(55.0, snap.getEstimatedGi(), 0.1);
        verify(off).searchProducts("Protein Bar XYZ", 5);
        verify(off, never()).lookupByBarcode(anyString());
    }

    @Test
    void barcodeFallsBackToOffLookup() throws Exception {
        OpenFoodFactsService off = mock(OpenFoodFactsService.class);
        OFFProductDto product = packagedBar();
        when(off.lookupByBarcode("3017620425035")).thenReturn(Optional.of(product));
        when(off.toNutritionSnapshot(eq(product), eq(50.0))).thenReturn(NutritionSnapshot.builder()
                .source("OPEN_FOOD_FACTS")
                .absorptionMode("GI_GL_ENHANCED")
                .confidence(0.9)
                .totalCarbs(20.0)
                .fiber(2.5)
                .protein(10.0)
                .fat(5.0)
                .estimatedGi(55.0)
                .glycemicLoad(9.6)
                .normalizedFoods(List.of(product.getProductName()))
                .build());

        NutritionSnapshot snap = service(off).compose(List.of(new FoodPortion("3017620425035", 50)));
        assertEquals("OPEN_FOOD_FACTS", snap.getSource());
        assertEquals(20.0, snap.getTotalCarbs(), 0.1);
        verify(off).lookupByBarcode("3017620425035");
        verify(off, never()).searchProducts(anyString(), anyInt());
    }

    @Test
    void mixedCatalogAndOffSources() throws Exception {
        OpenFoodFactsService off = mock(OpenFoodFactsService.class);
        OFFProductDto product = packagedBar();
        when(off.searchProducts("Protein Bar XYZ", 5)).thenReturn(List.of(product));
        when(off.toNutritionSnapshot(eq(product), eq(50.0))).thenReturn(NutritionSnapshot.builder()
                .source("OPEN_FOOD_FACTS")
                .absorptionMode("GI_GL_ENHANCED")
                .confidence(0.9)
                .totalCarbs(20.0)
                .fiber(2.5)
                .protein(10.0)
                .fat(5.0)
                .estimatedGi(50.0)
                .glycemicLoad(8.8)
                .normalizedFoods(List.of(product.getProductName()))
                .build());

        NutritionSnapshot snap = service(off).compose(List.of(
                new FoodPortion("apple", 100),
                new FoodPortion("Protein Bar XYZ", 50)));
        assertEquals("MIXED", snap.getSource());
        // apple ~14g carbs + bar 20g
        assertEquals(34.0, snap.getTotalCarbs(), 0.5);
    }

    @Test
    void unknownFoodStaysUnmatchedWhenOffMisses() throws Exception {
        OpenFoodFactsService off = mock(OpenFoodFactsService.class);
        when(off.searchProducts(anyString(), anyInt())).thenReturn(List.of());
        NutritionSnapshot snap = service(off).compose(List.of(new FoodPortion("zzzz-not-a-food", 100)));
        assertEquals("UNMATCHED", snap.getSource());
        assertEquals(0.0, snap.getTotalCarbs(), 0.01);
    }

    @Test
    void buckwheatUsesCatalogNotAbsurdOffHit() throws Exception {
        OpenFoodFactsService off = mock(OpenFoodFactsService.class);
        OFFProductDto junk = packagedBar();
        junk.setProductName("Buckwheat candy syrup");
        junk.getNutriments().setCarbohydrates100g(385.0); // impossible; caused 770 g for 200 g
        when(off.searchProducts(anyString(), anyInt())).thenReturn(List.of(junk));
        when(off.toNutritionSnapshot(any(), anyDouble())).thenAnswer(inv -> {
            OFFProductDto p = inv.getArgument(0);
            double g = inv.getArgument(1);
            return NutritionSnapshot.builder()
                    .source("OPEN_FOOD_FACTS")
                    .totalCarbs(p.getNutriments().getCarbohydrates100g() * g / 100.0)
                    .fiber(0.0).protein(0.0).fat(0.0)
                    .build();
        });

        NutritionSnapshot snap = service(off).compose(List.of(new FoodPortion("buckwheat", 200)));
        assertEquals("FOOD_CATALOG", snap.getSource());
        assertEquals(40.0, snap.getTotalCarbs(), 0.5); // cooked ~20 g/100 g
        verify(off, never()).searchProducts(anyString(), anyInt());
    }

    @Test
    void offFallbackRejectsImpossibleCarbsPer100g() throws Exception {
        OpenFoodFactsService off = mock(OpenFoodFactsService.class);
        OFFProductDto junk = packagedBar();
        junk.setProductName("Mystery syrup");
        junk.getNutriments().setCarbohydrates100g(385.0);
        OFFProductDto ok = packagedBar();
        ok.setProductName("Mystery bar");
        ok.getNutriments().setCarbohydrates100g(40.0);
        when(off.searchProducts("Mystery syrup xyz", 5)).thenReturn(List.of(junk, ok));
        when(off.toNutritionSnapshot(eq(ok), eq(50.0))).thenReturn(NutritionSnapshot.builder()
                .source("OPEN_FOOD_FACTS")
                .totalCarbs(20.0)
                .fiber(2.5)
                .protein(10.0)
                .fat(5.0)
                .estimatedGi(55.0)
                .build());

        NutritionSnapshot snap = service(off).compose(List.of(new FoodPortion("Mystery syrup xyz", 50)));
        assertEquals("OPEN_FOOD_FACTS", snap.getSource());
        assertEquals(20.0, snap.getTotalCarbs(), 0.1);
        verify(off).toNutritionSnapshot(eq(ok), eq(50.0));
    }
}
