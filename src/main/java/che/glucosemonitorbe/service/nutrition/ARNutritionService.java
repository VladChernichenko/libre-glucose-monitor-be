package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.dto.ARFoodItem;
import che.glucosemonitorbe.dto.ARNutritionRequest;
import che.glucosemonitorbe.dto.OFFProductDto;
import che.glucosemonitorbe.entity.OFFProductDocument;
import che.glucosemonitorbe.repository.OFFProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ConditionalOnProperty(name = "mongodb.enabled", havingValue = "true", matchIfMissing = true)
@Service
@Slf4j
@RequiredArgsConstructor
public class ARNutritionService {

    private final OFFProductRepository offProductRepository;
    private final NutritionEnrichmentService nutritionEnrichmentService;
    private final GlycemicPatternMatchingService glycemicPatternMatchingService;
    private final OpenFoodFactsService openFoodFactsService;

    /**
     * USDA fresh-weight macros for common whole produce and proteins.
     * Values: [carbs, fiber, protein, fat, gi] per 100g fresh/cooked weight.
     * Checked BEFORE OFF MongoDB to avoid matching dried/processed variants.
     */
    private static final Map<String, double[]> FRESH_NUTRITION_100G = Map.ofEntries(
        Map.entry("banana",      new double[]{23.0, 2.6, 1.1, 0.3,  60}),
        Map.entry("apple",       new double[]{14.0, 2.4, 0.3, 0.2,  36}),
        Map.entry("orange",      new double[]{12.0, 2.4, 0.9, 0.1,  43}),
        Map.entry("grape",       new double[]{17.2, 0.9, 0.7, 0.2,  59}),
        Map.entry("mango",       new double[]{15.0, 1.6, 0.8, 0.4,  56}),
        Map.entry("strawberry",  new double[]{ 7.7, 2.0, 0.7, 0.3,  40}),
        Map.entry("blueberry",   new double[]{14.5, 2.4, 0.7, 0.3,  53}),
        Map.entry("watermelon",  new double[]{ 7.5, 0.4, 0.6, 0.2,  76}),
        Map.entry("pear",        new double[]{15.5, 3.1, 0.4, 0.1,  38}),
        Map.entry("peach",       new double[]{ 9.5, 1.5, 0.9, 0.3,  42}),
        Map.entry("plum",        new double[]{11.4, 1.4, 0.7, 0.3,  40}),
        Map.entry("cherry",      new double[]{16.0, 2.1, 1.1, 0.3,  63}),
        Map.entry("kiwi",        new double[]{15.0, 3.0, 1.1, 0.5,  52}),
        Map.entry("pineapple",   new double[]{13.1, 1.4, 0.5, 0.1,  59}),
        Map.entry("pomegranate", new double[]{18.7, 4.0, 1.7, 1.2,  35}),
        Map.entry("carrot",      new double[]{10.0, 2.8, 0.9, 0.2,  35}),
        Map.entry("tomato",      new double[]{ 3.9, 1.2, 0.9, 0.2,  30}),
        Map.entry("cucumber",    new double[]{ 3.6, 0.5, 0.7, 0.1,  15}),
        Map.entry("broccoli",    new double[]{ 7.0, 2.6, 2.8, 0.4,  15}),
        Map.entry("spinach",     new double[]{ 3.6, 2.2, 2.9, 0.4,  15}),
        Map.entry("lettuce",     new double[]{ 2.9, 1.3, 1.4, 0.2,  15}),
        Map.entry("avocado",     new double[]{ 8.5, 6.7, 2.0,14.7,  10}),
        Map.entry("egg",         new double[]{ 1.1, 0.0,12.6,10.6,   0}),
        Map.entry("chicken",     new double[]{ 0.0, 0.0,31.0, 3.6,   0}),
        Map.entry("beef",        new double[]{ 0.0, 0.0,26.0,15.0,   0}),
        Map.entry("pork",        new double[]{ 0.0, 0.0,27.0,14.0,   0}),
        Map.entry("salmon",      new double[]{ 0.0, 0.0,25.4,13.4,   0}),
        Map.entry("tuna",        new double[]{ 0.0, 0.0,29.9, 6.3,   0}),
        Map.entry("shrimp",      new double[]{ 0.9, 0.0,20.1, 1.7,   0})
    );

    /**
     * Cooked-weight / dry-weight ratio per food label.
     * OFF macros are per-100g dry/raw weight; ARKit measures cooked/prepared volume.
     * Multiplying by this ratio converts dry per-100g values to cooked per-100g.
     */
    private static final Map<String, Double> COOKED_RATIO = Map.ofEntries(
        Map.entry("pasta",        0.35), Map.entry("макароны",     0.35),
        Map.entry("спагетти",     0.35), Map.entry("лапша",        0.35),
        Map.entry("noodles",      0.35), Map.entry("spaghetti",    0.35),
        Map.entry("rice",         0.35), Map.entry("рис",          0.35),
        Map.entry("brown rice",   0.35), Map.entry("коричневый рис", 0.35),
        Map.entry("barley",       0.40), Map.entry("перловка",     0.40),
        Map.entry("buckwheat",    0.40), Map.entry("гречка",       0.40),
        Map.entry("oatmeal",      0.40), Map.entry("овсянка",      0.40),
        Map.entry("porridge",     0.40), Map.entry("манка",        0.40),
        Map.entry("lentils",      0.40), Map.entry("beans",        0.40),
        Map.entry("chickpeas",    0.40), Map.entry("potato",       0.85),
        Map.entry("картофель",    0.85), Map.entry("картошка",     0.85)
    );

    public NutritionSnapshot analyze(ARNutritionRequest request) {
        List<NutritionSnapshot.FoodMassBreakdown> breakdown = new ArrayList<>();
        double totalCarbs = 0, totalFat = 0, totalProtein = 0, totalFiber = 0;
        double weightedGiNumerator = 0, weightedGiDenominator = 0;
        double totalSegConfidence = 0;
        int offHits = 0;

        for (ARFoodItem item : request.getFoods()) {
            NutritionSnapshot.FoodMassBreakdown bd = resolveItem(item);
            breakdown.add(bd);
            totalCarbs   += bd.getCarbs();
            totalFat     += bd.getFat();
            totalProtein += bd.getProtein();
            totalFiber   += bd.getFiber();
            totalSegConfidence += item.getSegmentationConfidence();
            if (bd.getOffProductName() != null) offHits++;
            if (bd.getCarbs() > 0) {
                weightedGiNumerator   += bd.getGi() * bd.getCarbs();
                weightedGiDenominator += bd.getCarbs();
            }
        }

        int n = request.getFoods().size();
        double avgSegConfidence = n > 0 ? totalSegConfidence / n : 0.6;
        // Macro confidence: 0.75 if ≥1 OFF hit, 0.40 if all keyword fallback
        double macroConfidence = offHits > 0 ? 0.75 : 0.40;
        double confidence = Math.min(avgSegConfidence > 0 ? avgSegConfidence : 0.65, macroConfidence);

        double gi = weightedGiDenominator > 0 ? weightedGiNumerator / weightedGiDenominator : 55.0;
        double availCarbs = Math.max(0, totalCarbs - totalFiber);
        double gl = gi * availCarbs / 100.0;

        List<String> foods = breakdown.stream().map(NutritionSnapshot.FoodMassBreakdown::getLabel).toList();

        log.info("[AR-OFF] foods={} offHits={}/{} totalCarbs={} gi={} confidence={}",
                foods, offHits, n, round1(totalCarbs), round1(gi), round1(confidence));

        NutritionSnapshot snapshot = NutritionSnapshot.builder()
                .absorptionMode("GI_GL_ENHANCED")
                .source("AR_OFF")
                .confidence(round1(confidence))
                .totalCarbs(round1(totalCarbs))
                .fat(round1(totalFat))
                .protein(round1(totalProtein))
                .fiber(round1(totalFiber))
                .estimatedGi(round1(gi))
                .glycemicLoad(round1(gl))
                .absorptionSpeedClass(classify(gi, totalFiber, totalProtein, totalFat))
                .normalizedFoods(foods)
                .foodMassBreakdown(breakdown)
                .build();

        return glycemicPatternMatchingService.enrich(snapshot);
    }

    private NutritionSnapshot.FoodMassBreakdown resolveItem(ARFoodItem item) {
        String label = item.getLabel().toLowerCase().trim();
        double massG = item.getMassG();

        // Check fresh-produce table first — avoids OFF matching dried/processed variants
        double[] fresh = FRESH_NUTRITION_100G.get(label);
        if (fresh == null) {
            // Try partial match: "ripe banana" → "banana"
            fresh = FRESH_NUTRITION_100G.entrySet().stream()
                    .filter(e -> label.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
        if (fresh != null) {
            double scale = massG / 100.0;
            log.debug("[AR-FRESH] label='{}' massG={} carbs={}", label, massG, round1(fresh[0] * scale));
            return NutritionSnapshot.FoodMassBreakdown.builder()
                    .label(item.getLabel())
                    .massG(massG)
                    .carbs(round1(fresh[0] * scale))
                    .fiber(round1(fresh[1] * scale))
                    .protein(round1(fresh[2] * scale))
                    .fat(round1(fresh[3] * scale))
                    .gi(fresh[4])
                    .offProductName("USDA fresh")
                    .confidence(0.80)
                    .build();
        }

        List<OFFProductDocument> hits = offProductRepository.findByProductNameContaining(
                label, PageRequest.of(0, 3));

        if (!hits.isEmpty()) {
            OFFProductDocument doc = hits.get(0);
            OFFProductDocument.Nutriments n = doc.getNutriments();
            if (n != null) {
                double ratio = COOKED_RATIO.getOrDefault(label, 1.0);
                double scale = massG / 100.0 * ratio;
                double carbs   = safe(n.getCarbohydrates100g()) * scale;
                double fat     = safe(n.getFat100g())           * scale;
                double protein = safe(n.getProteins100g())      * scale;
                double fiber   = safe(n.getFiber100g())         * scale;

                OFFProductDto dto = new OFFProductDto();
                dto.setProductName(doc.getProductName());
                dto.setCategoriesTags(doc.getCategoriesTags());
                NutritionSnapshot giSnap = openFoodFactsService.toNutritionSnapshot(dto, massG * ratio);
                double gi = giSnap.getEstimatedGi() != null ? giSnap.getEstimatedGi() : 55.0;

                log.debug("[AR-OFF] label='{}' hit='{}' massG={} ratio={} carbs={} gi={}",
                        label, doc.getProductName(), massG, ratio, round1(carbs), round1(gi));

                return NutritionSnapshot.FoodMassBreakdown.builder()
                        .label(item.getLabel())
                        .massG(massG)
                        .carbs(round1(carbs))
                        .fat(round1(fat))
                        .protein(round1(protein))
                        .fiber(round1(fiber))
                        .gi(round1(gi))
                        .offProductName(doc.getProductName())
                        .confidence(0.75)
                        .build();
            }
        }

        // Keyword fallback
        NutritionSnapshot fallback = nutritionEnrichmentService.enrichFromText(label, "", massG * 0.15);
        log.debug("[AR-OFF] label='{}' no OFF hit, keyword fallback carbs={}", label,
                fallback.getTotalCarbs());

        return NutritionSnapshot.FoodMassBreakdown.builder()
                .label(item.getLabel())
                .massG(massG)
                .carbs(round1(safe(fallback.getTotalCarbs())))
                .fat(round1(safe(fallback.getFat())))
                .protein(round1(safe(fallback.getProtein())))
                .fiber(round1(safe(fallback.getFiber())))
                .gi(fallback.getEstimatedGi() != null ? fallback.getEstimatedGi() : 55.0)
                .offProductName(null)
                .confidence(0.40)
                .build();
    }

    private static double safe(Double v)   { return v != null ? v : 0.0; }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static String classify(double gi, double fiber, double protein, double fat) {
        if (fiber >= 8 || (protein + fat) >= 20) return "SLOW";
        if (gi >= 70 && fiber < 4)               return "FAST";
        return "MEDIUM";
    }
}
