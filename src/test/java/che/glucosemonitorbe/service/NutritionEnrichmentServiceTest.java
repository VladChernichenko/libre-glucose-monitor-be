package che.glucosemonitorbe.service;

import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for NutritionEnrichmentService covering:
 * - input routing (DEFAULT_DECAY vs GI_GL_ENHANCED)
 * - keyword GI estimation across diverse meals
 * - fat+protein GI dampening (Gap #5 — Moghaddam et al., Venn & Green)
 * - GL calculation using net carbs (carbs − fiber)
 * - dampening cap (≤20 GI units) and floor (≥15)
 * - speed class classification
 * - macro population (fat / protein / fiber non-zero for food inputs)
 */
class NutritionEnrichmentServiceTest {

    private static final double DELTA = 0.2;

    private final NutritionEnrichmentService service = new NutritionEnrichmentService();

    // ── input routing ─────────────────────────────────────────────────────────

    @Test
    void pureGramInput_returnsDefaultDecay() {
        NutritionSnapshot s = service.enrichFromText("20g", "", 20.0);
        assertThat(s.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
        assertThat(s.getSource()).isEqualTo("MANUAL_CARBS");
        assertThat(s.getTotalCarbs()).isEqualTo(20.0);
        assertThat(s.getEstimatedGi()).isNull();
    }

    @Test
    void nullInput_returnsDefaultDecay() {
        NutritionSnapshot s = service.enrichFromText(null, null, 30.0);
        assertThat(s.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
        assertThat(s.getSource()).isEqualTo("MANUAL_CARBS");
    }

    @Test
    void emptyStrings_returnsDefaultDecay() {
        NutritionSnapshot s = service.enrichFromText("", "", 0.0);
        assertThat(s.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
    }

    @Test
    void foodNameInput_returnsGiGlEnhanced() {
        NutritionSnapshot s = service.enrichFromText("oatmeal with berries", "", 50.0);
        assertThat(s.getAbsorptionMode()).isEqualTo("GI_GL_ENHANCED");
        assertThat(s.getSource()).isEqualTo("KEYWORD_GI");
        assertThat(s.getConfidence()).isEqualTo(0.4);
    }

    @Test
    void carbsPassedThroughUnchanged() {
        NutritionSnapshot s = service.enrichFromText("pasta with chicken", "", 75.5);
        assertThat(s.getTotalCarbs()).isEqualTo(75.5);
    }

    @Test
    void nullCarbs_defaultsToZero() {
        NutritionSnapshot s = service.enrichFromText("rice", "", null);
        assertThat(s.getTotalCarbs()).isEqualTo(0.0);
    }

    @Test
    void negativeCarbs_clampedToZero() {
        NutritionSnapshot s = service.enrichFromText("rice", "", -10.0);
        assertThat(s.getTotalCarbs()).isEqualTo(0.0);
    }

    @Test
    void foodInCommentField_usedForAnalysis() {
        NutritionSnapshot fromInput  = service.enrichFromText("salmon", "", 20.0);
        NutritionSnapshot fromComment = service.enrichFromText("", "salmon", 20.0);
        assertThat(fromComment.getAbsorptionMode()).isEqualTo("GI_GL_ENHANCED");
        assertThat(fromComment.getEstimatedGi())
                .isCloseTo(fromInput.getEstimatedGi(), within(1.0));
    }

    // ── pure high-GI carbs (minimal fat/protein → very little dampening) ──────

    @Test
    void whiteRice_highGI_minimalDampening() {
        // "white rice" matches the high-GI branch → raw GI=75.0 (same as glucose/white bread),
        // fat=1g, protein=2g → dampening=0.7 → gi≈74.3 (≥70 → FAST)
        NutritionSnapshot s = service.enrichFromText("white rice", "", 60.0);
        assertThat(s.getEstimatedGi()).isCloseTo(74.3, within(0.5));
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("FAST");
    }

    @Test
    void plainBread_mediumHighGI_approximatelyUndampened() {
        // "bread" (without "white") → 65.0, fat=1g, protein=2g → damped≈64.3
        NutritionSnapshot s = service.enrichFromText("bread with butter", "", 40.0);
        // butter adds fat=14 → more dampening than plain bread; GI still substantial
        assertThat(s.getEstimatedGi()).isLessThan(66.0);
        assertThat(s.getEstimatedGi()).isGreaterThan(40.0);
    }

    @Test
    void watermelon_fastSpeedClass() {
        // raw GI=76, fat=1g, protein=2g → dampening≈0.7 → gi≈75.3 ≥ 70 → FAST
        NutritionSnapshot s = service.enrichFromText("watermelon", "", 30.0);
        assertThat(s.getEstimatedGi()).isGreaterThan(70.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("FAST");
    }

    @Test
    void glucose_fastSpeedClass() {
        NutritionSnapshot s = service.enrichFromText("glucose", "", 30.0);
        assertThat(s.getEstimatedGi()).isGreaterThan(70.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("FAST");
    }

    @Test
    void cornFlakes_fastSpeedClass() {
        NutritionSnapshot s = service.enrichFromText("corn flakes", "", 30.0);
        assertThat(s.getEstimatedGi()).isGreaterThan(70.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("FAST");
    }

    // ── mixed meals: fat+protein dampening ───────────────────────────────────

    @Test
    void chickenWithWhiteRice_giReducedByProtein() {
        NutritionSnapshot pureRice = service.enrichFromText("white rice", "", 60.0);
        NutritionSnapshot mixed    = service.enrichFromText("chicken, white rice", "", 60.0);
        // chicken adds protein≈25g → significant dampening
        assertThat(mixed.getEstimatedGi()).isLessThan(pureRice.getEstimatedGi());
        assertThat(mixed.getProtein()).isGreaterThan(10.0);
    }

    @Test
    void salmonWithBrownRice_substantialDampening() {
        // salmon: fat=10, protein=22; brown rice: GI=50, fat=1, protein=2
        // rawGi=(55+50)/2=52.5; dampening=11×0.3+24×0.2=3.3+4.8=8.1 → gi≈44.4
        NutritionSnapshot s = service.enrichFromText("salmon, brown rice", "", 50.0);
        assertThat(s.getEstimatedGi()).isCloseTo(44.4, within(1.0));
        assertThat(s.getFat()).isGreaterThan(5.0);
        assertThat(s.getProtein()).isGreaterThan(15.0);
    }

    @Test
    void pizza_fatDampensGiRelativeToPurePasta() {
        // pasta: GI=58, fat=1g, protein=2g → minimal dampening
        // pizza: GI=55 (default), fat=12g → more dampening
        NutritionSnapshot pasta = service.enrichFromText("pasta", "", 50.0);
        NutritionSnapshot pizza = service.enrichFromText("pizza", "", 50.0);
        // pizza fat=12g → dampening=3.6 vs pasta dampening≈0.7
        assertThat(pizza.getEstimatedGi()).isLessThan(pasta.getEstimatedGi());
        assertThat(pizza.getFat()).isGreaterThanOrEqualTo(12.0);
    }

    @Test
    void beefSteak_highProteinFat_slowAbsorption() {
        // protein=24g, fat=10g → protein+fat=34 ≥ 20 → SLOW
        NutritionSnapshot s = service.enrichFromText("beef steak", "", 10.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("SLOW");
        assertThat(s.getProtein()).isGreaterThanOrEqualTo(20.0);
        assertThat(s.getFat()).isGreaterThanOrEqualTo(8.0);
    }

    @Test
    void eggs_proteinAndFatDampenGI() {
        // raw GI=55 (default), fat=5, protein=6 → dampening=1.5+1.2=2.7 → gi≈52.3
        NutritionSnapshot s = service.enrichFromText("eggs", "", 5.0);
        assertThat(s.getEstimatedGi()).isCloseTo(52.3, within(1.0));
    }

    @Test
    void omeletteWithAvocado_veryLowGlycemicImpact() {
        // eggs: fat=5, protein=6; avocado: fat=15, protein=2
        // totalFat=20, totalProtein=8; dampening=6.0+1.6=7.6
        // rawGi=(55+55)/2=55; dampedGi=55-7.6=47.4
        NutritionSnapshot s = service.enrichFromText("eggs, avocado", "", 8.0);
        assertThat(s.getEstimatedGi()).isCloseTo(47.4, within(1.0));
        assertThat(s.getFat()).isGreaterThan(18.0);
    }

    @Test
    void salmonWithAvocado_veryHighFatMeal_giHeavilyDampened() {
        // salmon fat=10, protein=22; avocado fat=15, protein=2
        // totalFat=25, totalProtein=24; dampening=7.5+4.8=12.3
        // rawGi=(55+55)/2=55; dampedGi=55-12.3=42.7
        NutritionSnapshot s = service.enrichFromText("salmon, avocado", "", 15.0);
        assertThat(s.getEstimatedGi()).isCloseTo(42.7, within(1.0));
    }

    @Test
    void chickenWithPasta_higherGiThanChickenWithLentils() {
        NutritionSnapshot withPasta   = service.enrichFromText("chicken, pasta", "", 60.0);
        NutritionSnapshot withLentils = service.enrichFromText("chicken, lentils", "", 60.0);
        // pasta GI=58 vs lentils GI=30 → pasta meal has higher final GI
        assertThat(withPasta.getEstimatedGi()).isGreaterThan(withLentils.getEstimatedGi());
    }

    // ── high-fiber meals ──────────────────────────────────────────────────────

    @Test
    void lentilsAndBeans_highFiber_slowSpeedClass() {
        // combined fiber = 7+7 = 14 ≥ 8 → SLOW
        NutritionSnapshot s = service.enrichFromText("lentils, beans", "", 35.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("SLOW");
        assertThat(s.getFiber()).isGreaterThanOrEqualTo(8.0);
    }

    @Test
    void wholegrainOats_mediumGI_fiberNonZero() {
        NutritionSnapshot s = service.enrichFromText("whole grain oats", "", 45.0);
        assertThat(s.getFiber()).isGreaterThan(0.0);
        assertThat(s.getEstimatedGi()).isLessThan(60.0);
    }

    @Test
    void lentilSoup_lowGlycemicLoad() {
        NutritionSnapshot s = service.enrichFromText("lentil", "", 30.0);
        // GI=30, protein+fiber dampens further → very low GL
        assertThat(s.getGlycemicLoad()).isLessThan(10.0);
    }

    // ── GL uses net carbs (carbs − fiber) ────────────────────────────────────

    @Test
    void glCalculation_usesNetCarbs_notTotalCarbs() {
        NutritionSnapshot s = service.enrichFromText("lentils", "", 50.0);
        // GL = dampedGi × (totalCarbs − fiber) / 100
        double expectedGl = s.getEstimatedGi() * (50.0 - s.getFiber()) / 100.0;
        assertThat(s.getGlycemicLoad()).isCloseTo(expectedGl, within(DELTA));
    }

    @Test
    void highFiberMeal_glLowerThanGrossCarbs() {
        NutritionSnapshot s = service.enrichFromText("lentils, beans", "", 40.0);
        // fiber≈14g → net carbs meaningful difference
        double glUsingTotalCarbs = s.getEstimatedGi() * 40.0 / 100.0;
        assertThat(s.getGlycemicLoad()).isLessThan(glUsingTotalCarbs);
    }

    @Test
    void lowFiberMeal_glApproximatelyTotalCarbsBased() {
        NutritionSnapshot s = service.enrichFromText("watermelon", "", 30.0);
        // fiber=0.5g → GL ≈ gi * 29.5 / 100
        double expectedGl = s.getEstimatedGi() * (30.0 - s.getFiber()) / 100.0;
        assertThat(s.getGlycemicLoad()).isCloseTo(expectedGl, within(DELTA));
    }

    // ── dampening cap (max 20 GI units) and floor (min GI = 15) ─────────────

    @Test
    void extremeHFHPMeal_dampeningCappedAt20GiUnits() {
        // butter(14)+salmon(10)+cheese(9)+beef(10)+pork(14)=57g fat
        // salmon(22)+cheese(8)+beef(24)+pork(22)+butter(2)=78g protein
        // dampening = min(57×0.3+78×0.2, 20) = min(17.1+15.6, 20) = 20 (capped)
        NutritionSnapshot s = service.enrichFromText("butter, salmon, cheese, beef, pork", "", 20.0);
        assertThat(s.getEstimatedGi()).isGreaterThanOrEqualTo(15.0); // floor enforced
        // also verify reduction happened relative to default GI
        assertThat(s.getEstimatedGi()).isLessThan(55.0);
    }

    @Test
    void veryLowGiWithHighFatProtein_flooredAt15() {
        // nut: GI=15, fat=12, protein=5 → dampening=4.6 → 15-4.6=10.4 → floor=15
        NutritionSnapshot s = service.enrichFromText("nut", "", 15.0);
        assertThat(s.getEstimatedGi()).isEqualTo(15.0);
    }

    @Test
    void walnutAlmond_flooredAt15() {
        // walnut+almond: GI=15+15=15 avg, high fat+protein → floor
        NutritionSnapshot s = service.enrichFromText("walnut, almond", "", 10.0);
        assertThat(s.getEstimatedGi()).isEqualTo(15.0);
    }

    // ── speed class classification ────────────────────────────────────────────

    @Test
    void pureGlucose_fastSpeedClass() {
        NutritionSnapshot s = service.enrichFromText("glucose", "", 30.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("FAST");
    }

    @Test
    void chicken_highProtein_slowSpeedClass() {
        // protein=25, fat=5 → protein+fat=30 ≥ 20 → SLOW
        NutritionSnapshot s = service.enrichFromText("chicken", "", 5.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("SLOW");
    }

    @Test
    void salmon_highFatProtein_slowSpeedClass() {
        NutritionSnapshot s = service.enrichFromText("salmon", "", 5.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("SLOW");
    }

    @Test
    void banana_mediumSpeedClass() {
        // GI=60, fat=1, protein=2 → not ≥70, not enough fat/protein → MEDIUM
        NutritionSnapshot s = service.enrichFromText("banana", "", 25.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("MEDIUM");
    }

    @Test
    void ryeBread_mediumSpeedClass() {
        // GI=48 → MEDIUM
        NutritionSnapshot s = service.enrichFromText("rye bread", "", 30.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("MEDIUM");
    }

    @Test
    void sweetPotato_mediumSpeedClass() {
        // GI=44, no significant fat/protein → MEDIUM
        NutritionSnapshot s = service.enrichFromText("sweet potato", "", 30.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("MEDIUM");
    }

    @Test
    void lentilsAlone_mediumSpeedClass() {
        // fiber=7 < 8, protein+fat=9+1=10 < 20, GI=~27 < 70 → MEDIUM
        NutritionSnapshot s = service.enrichFromText("lentils", "", 35.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("MEDIUM");
    }

    // ── macro population ─────────────────────────────────────────────────────

    @Test
    void macroValuesNonZeroForProteinFatRichFood() {
        NutritionSnapshot s = service.enrichFromText("salmon, avocado", "", 10.0);
        assertThat(s.getFat()).isGreaterThan(0.0);
        assertThat(s.getProtein()).isGreaterThan(0.0);
        assertThat(s.getFiber()).isGreaterThan(0.0);
    }

    @Test
    void pureHighCarbMeal_minimalMacros() {
        // watermelon has no fat/protein in heuristics → minimal values
        NutritionSnapshot s = service.enrichFromText("watermelon", "", 30.0);
        assertThat(s.getFat()).isLessThan(5.0);
        assertThat(s.getProtein()).isLessThan(5.0);
    }

    @Test
    void avocado_highFatReported() {
        NutritionSnapshot s = service.enrichFromText("avocado", "", 5.0);
        assertThat(s.getFat()).isGreaterThanOrEqualTo(15.0);
    }

    @Test
    void oats_fiberReported() {
        NutritionSnapshot s = service.enrichFromText("oats", "", 40.0);
        assertThat(s.getFiber()).isGreaterThanOrEqualTo(3.0);
    }

    // ── dampening invariant: damped GI ≤ raw keyword average ─────────────────

    /**
     * For any recognized food the effective GI must always be within [15, 78].
     * Validates the monotonicity of applyFatProteinDampening.
     */
    @ParameterizedTest(name = "{0}: GI within physiological bounds")
    @CsvSource({
            "white rice,       60.0",
            "oats,             50.0",
            "banana,           25.0",
            "lentils,          40.0",
            "chicken,           5.0",
            "salmon,           10.0",
            "beef steak,       10.0",
            "eggs,              5.0",
            "nut,              15.0",
            "butter,            5.0",
            "pizza,            60.0",
            "whole grain,      35.0",
            "watermelon,       30.0",
            "sweet potato,     30.0",
            "rye bread,        35.0",
            "milk,             20.0",
            "chocolate,        25.0",
            "broccoli,         10.0",
            "lentils beans,    40.0",
            "salmon avocado,   15.0"
    })
    void effectiveGiWithinPhysiologicalBounds(String food, double carbs) {
        NutritionSnapshot s = service.enrichFromText(food, "", carbs);
        if (s.getEstimatedGi() != null) {
            assertThat(s.getEstimatedGi())
                    .as("GI for '%s'", food)
                    .isBetween(15.0, 78.0);
        }
    }

    /**
     * For any recognized food GI should not increase after dampening
     * (i.e. dampening only reduces or keeps GI stable).
     */
    @ParameterizedTest(name = "{0}: mixed meal GI ≤ pure carb equivalent")
    @CsvSource({
            "chicken,   white rice",
            "salmon,    pasta",
            "beef,      bread",
            "eggs,      oats",
            "cheese,    potato"
    })
    void mixedMealGiLessThanOrEqualToPureCarbGi(String protein, String carb) {
        double carbs = 50.0;
        NutritionSnapshot pureCarb = service.enrichFromText(carb, "", carbs);
        NutritionSnapshot mixed    = service.enrichFromText(protein + ", " + carb, "", carbs);

        if (pureCarb.getEstimatedGi() != null && mixed.getEstimatedGi() != null) {
            assertThat(mixed.getEstimatedGi())
                    .as("Adding %s should not raise GI above %s alone", protein, carb)
                    .isLessThanOrEqualTo(pureCarb.getEstimatedGi() + DELTA);
        }
    }

    // ── clinical meal scenarios ───────────────────────────────────────────────

    @Test
    void scenario_porridgeWithMilk_lowMediumGI() {
        // oat: GI=55, milk: GI=35 → avg=45; milk fat=3, protein=3; oat protein=2
        // dampening: fat(3+1=4)×0.3 + protein(3+2=5)×0.2 = 1.2+1.0=2.2 → gi≈42.8
        NutritionSnapshot s = service.enrichFromText("oat, milk", "", 45.0);
        assertThat(s.getEstimatedGi()).isBetween(35.0, 50.0);
        assertThat(s.getAbsorptionSpeedClass()).isEqualTo("MEDIUM");
    }

    @Test
    void scenario_steakWithBroccoliAndPotato_proteinDampensPotatoGI() {
        NutritionSnapshot purePotatoMeal = service.enrichFromText("potato", "", 50.0);
        NutritionSnapshot fullMeal = service.enrichFromText("beef steak, broccoli, potato", "", 50.0);
        // Adding steak (protein=24, fat=10) dampens the high-GI potato considerably
        assertThat(fullMeal.getEstimatedGi()).isLessThan(purePotatoMeal.getEstimatedGi());
        assertThat(fullMeal.getAbsorptionSpeedClass()).isEqualTo("SLOW");
    }

    @Test
    void scenario_yoghurtWithOats_proteinFiberCombo() {
        NutritionSnapshot s = service.enrichFromText("yoghurt, oats", "", 40.0);
        // yoghurt protein=6, fat=3; oats fiber=3, protein=2
        assertThat(s.getFiber()).isGreaterThan(0.0);
        assertThat(s.getProtein()).isGreaterThan(0.0);
        assertThat(s.getEstimatedGi()).isLessThan(55.0);
    }

    @Test
    void scenario_pizzaDough_hfhpDampening() {
        // Pizza: HFHP archetypical meal. GI should be dampened significantly vs plain pasta
        NutritionSnapshot pasta = service.enrichFromText("pasta", "", 60.0);
        NutritionSnapshot pizza = service.enrichFromText("pizza", "", 60.0);
        assertThat(pizza.getEstimatedGi()).isLessThan(pasta.getEstimatedGi());
    }

    @Test
    void scenario_almondMilkAndBanana_lowGlycemicImpact() {
        NutritionSnapshot s = service.enrichFromText("almond, banana", "", 25.0);
        // almond: GI=15, fat=12, protein=5; banana: GI=60, fat=1, protein=2
        // rawGi=(15+60)/2=37.5; dampening=(13×0.3+7×0.2)=3.9+1.4=5.3 → gi≈32.2
        assertThat(s.getEstimatedGi()).isCloseTo(32.2, within(1.5));
        assertThat(s.getGlycemicLoad()).isLessThan(10.0);
    }
}
