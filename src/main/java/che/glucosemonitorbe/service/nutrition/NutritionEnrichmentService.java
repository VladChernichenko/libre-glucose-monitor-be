package che.glucosemonitorbe.service.nutrition;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class NutritionEnrichmentService {
    private static final Pattern CARB_GRAM_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*g\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LETTER_PATTERN = Pattern.compile("[a-zA-Z]{3,}");
    private static final double DEFAULT_GI = 55.0;

    public NutritionSnapshot enrichFromText(String detailedInput, String comment, Double fallbackCarbs) {
        String text = ((detailedInput != null ? detailedInput : "") + " " + (comment != null ? comment : "")).trim();
        double carbs = fallbackCarbs != null ? Math.max(0.0, fallbackCarbs) : 0.0;
        boolean hasFoodEntities = hasFoodEntities(text);

        if (!hasFoodEntities) {
            log.debug("Nutrition enrichment: no food entities detected, textLength={}", text.length());
            return NutritionSnapshot.builder()
                    .absorptionMode("DEFAULT_DECAY")
                    .source("MANUAL_CARBS")
                    .confidence(1.0)
                    .totalCarbs(carbs)
                    .fiber(0.0)
                    .protein(0.0)
                    .fat(0.0)
                    .estimatedGi(null)
                    .glycemicLoad(null)
                    .absorptionSpeedClass("DEFAULT")
                    .normalizedFoods(List.of())
                    .build();
        }

        List<String> foods = extractFoodTokens(text);
        double rawGi     = estimateGiFromFoods(foods);
        double fat       = estimateFatFromFoods(foods);
        double protein   = estimateProteinFromFoods(foods);
        double fiber     = estimateFiberFromFoods(foods);

        // Gap #5: fat+protein dampen the glycemic response in mixed meals.
        // Literature (Moghaddam et al.; Venn & Green): simple GI averages overestimate
        // peak glucose by 22–50% when fat/protein delay gastric emptying.
        // Formula caps total dampening at 20 GI units; floor at 15 (minimum physiological GI).
        double estimatedGi = applyFatProteinDampening(rawGi, fat, protein);

        double availableCarbs = Math.max(0.0, carbs - fiber);
        double gl = (estimatedGi * availableCarbs) / 100.0;

        log.info("Nutrition enrichment: foods={} rawGi={} dampedGi={} fat={}g protein={}g fiber={}g gl={}",
                foods, round1(rawGi), round1(estimatedGi), round1(fat), round1(protein), round1(fiber), round1(gl));

        return NutritionSnapshot.builder()
                .absorptionMode("GI_GL_ENHANCED")
                .source("KEYWORD_GI")
                .confidence(0.4)
                .totalCarbs(carbs)
                .fiber(round1(fiber))
                .protein(round1(protein))
                .fat(round1(fat))
                .estimatedGi(round1(estimatedGi))
                .glycemicLoad(round1(gl))
                .absorptionSpeedClass(classify(estimatedGi, fiber, protein, fat))
                .normalizedFoods(foods)
                .build();
    }

    private boolean hasFoodEntities(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Matcher carbMatcher = CARB_GRAM_PATTERN.matcher(text);
        boolean hasCarbToken = carbMatcher.find();
        Matcher letters = LETTER_PATTERN.matcher(text);
        return letters.find() && !(hasCarbToken && text.trim().matches("(?i)^\\s*\\d+(?:\\.\\d+)?\\s*g\\s*$"));
    }

    private double estimateGiFromFoods(List<String> foods) {
        if (foods.isEmpty()) {
            return DEFAULT_GI;
        }
        double sum = 0.0;
        for (String food : foods) {
            sum += giForFood(food);
        }
        return sum / foods.size();
    }

    private double giForFood(String food) {
        // High GI (70+)
        if (food.contains("white bread") || food.contains("white rice") || food.contains("glucose")
                || food.contains("corn flakes") || food.contains("waffle") || food.contains("pretzel")) return 75.0;
        if (food.contains("potato") && !food.contains("sweet")) return 78.0;
        if (food.contains("watermelon")) return 76.0;

        // Medium-high GI (56–69)
        if (food.contains("bread") && !food.contains("whole") && !food.contains("rye")
                && !food.contains("sourdough")) return 65.0;
        if (food.contains("rice") && !food.contains("brown")) return 64.0;
        if (food.contains("banana") || food.contains("pineapple")) return 60.0;
        if (food.contains("pasta") || food.contains("noodle")) return 58.0;

        // Low-medium GI (40–55)
        if (food.contains("whole grain") || food.contains("whole wheat") || food.contains("wholegrain")) return 50.0;
        if (food.contains("brown rice")) return 50.0;
        if (food.contains("rye") || food.contains("sourdough")) return 48.0;
        if (food.contains("oat") || food.contains("porridge")) return 55.0;
        if (food.contains("sweet potato")) return 44.0;
        if (food.contains("orange") || food.contains("apple") || food.contains("pear")) return 36.0;
        if (food.contains("milk") || food.contains("yogurt") || food.contains("yoghurt")) return 35.0;

        // Low GI (<40)
        if (food.contains("lentil") || food.contains("bean") || food.contains("chickpea")
                || food.contains("legume")) return 30.0;
        if (food.contains("nut") || food.contains("almond") || food.contains("walnut")) return 15.0;
        if (food.contains("vegetable") || food.contains("broccoli") || food.contains("spinach")
                || food.contains("salad") || food.contains("cucumber")) return 15.0;

        return DEFAULT_GI;
    }

    /**
     * Reduces effective GI for mixed meals containing fat and/or protein.
     * Fat delays gastric emptying; protein stimulates insulin and blunts the glucose peak.
     * Evidence: Moghaddam et al. 2006 (AJCN), Venn & Green 2007 — fat+protein can reduce
     * observed glycemic response by 22–50% relative to the carb-only GI prediction.
     *
     * Dampening: 0.30 per fat gram + 0.20 per protein gram, capped at 20 GI units.
     * Floor: 15 — the minimum physiologically plausible GI for any food.
     */
    private double applyFatProteinDampening(double rawGi, double fat, double protein) {
        double dampening = Math.min(fat * 0.30 + protein * 0.20, 20.0);
        return Math.max(rawGi - dampening, 15.0);
    }

    private double estimateFatFromFoods(List<String> foods) {
        double sum = 0.0;
        for (String food : foods) sum += fatForFood(food);
        return sum;
    }

    private double estimateProteinFromFoods(List<String> foods) {
        double sum = 0.0;
        for (String food : foods) sum += proteinForFood(food);
        return sum;
    }

    private double estimateFiberFromFoods(List<String> foods) {
        double sum = 0.0;
        for (String food : foods) sum += fiberForFood(food);
        return sum;
    }

    private double fatForFood(String food) {
        if (food.contains("butter") || food.contains("ghee")) return 14.0;
        if (food.contains("oil")) return 10.0;
        if (food.contains("avocado")) return 15.0;
        if (food.contains("cheese")) return 9.0;
        if (food.contains("nut") || food.contains("almond") || food.contains("walnut")
                || food.contains("cashew") || food.contains("peanut")) return 12.0;
        if (food.contains("salmon") || food.contains("mackerel")) return 10.0;
        if (food.contains("tuna") || food.contains("sardine")) return 5.0;
        if (food.contains("egg")) return 5.0;
        if (food.contains("pizza")) return 12.0;
        if (food.contains("beef") || food.contains("steak") || food.contains("mince")) return 10.0;
        if (food.contains("pork") || food.contains("bacon") || food.contains("sausage")) return 14.0;
        if (food.contains("chicken") || food.contains("turkey")) return 5.0;
        if (food.contains("lamb") || food.contains("meat")) return 10.0;
        if (food.contains("yogurt") || food.contains("yoghurt")) return 3.0;
        if (food.contains("milk")) return 3.0;
        if (food.contains("cream") || food.contains("sour cream")) return 10.0;
        if (food.contains("chocolate")) return 8.0;
        return 1.0;
    }

    private double proteinForFood(String food) {
        if (food.contains("chicken") || food.contains("turkey")) return 25.0;
        if (food.contains("beef") || food.contains("steak") || food.contains("mince")) return 24.0;
        if (food.contains("pork") || food.contains("lamb") || food.contains("meat")) return 22.0;
        if (food.contains("salmon") || food.contains("tuna") || food.contains("fish")
                || food.contains("sardine") || food.contains("mackerel")) return 22.0;
        if (food.contains("shrimp") || food.contains("prawn") || food.contains("seafood")) return 18.0;
        if (food.contains("tofu") || food.contains("tempeh")) return 10.0;
        if (food.contains("egg")) return 6.0;
        if (food.contains("cheese")) return 8.0;
        if (food.contains("yogurt") || food.contains("yoghurt")) return 6.0;
        if (food.contains("milk")) return 3.0;
        if (food.contains("lentil") || food.contains("bean") || food.contains("chickpea")
                || food.contains("legume")) return 9.0;
        if (food.contains("nut") || food.contains("almond") || food.contains("walnut")
                || food.contains("cashew") || food.contains("peanut")) return 5.0;
        if (food.contains("sausage") || food.contains("bacon")) return 12.0;
        return 2.0;
    }

    private double fiberForFood(String food) {
        if (food.contains("lentil") || food.contains("bean") || food.contains("chickpea")
                || food.contains("legume")) return 7.0;
        if (food.contains("broccoli") || food.contains("spinach") || food.contains("kale")
                || food.contains("cabbage")) return 3.0;
        if (food.contains("vegetable") || food.contains("salad") || food.contains("cucumber")) return 2.0;
        if (food.contains("whole grain") || food.contains("whole wheat") || food.contains("wholegrain")
                || food.contains("rye")) return 4.0;
        if (food.contains("oat") || food.contains("porridge")) return 3.0;
        if (food.contains("apple") || food.contains("pear")) return 3.0;
        if (food.contains("orange") || food.contains("berr")) return 2.0;
        if (food.contains("nut") || food.contains("almond") || food.contains("walnut")) return 2.0;
        if (food.contains("sweet potato")) return 3.0;
        if (food.contains("banana")) return 1.5;
        return 0.5;
    }

    private List<String> extractFoodTokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        return List.of(text.toLowerCase(Locale.ROOT).split("[,;]+\\s*"));
    }

    private String classify(double gi, double fiber, double protein, double fat) {
        if (fiber >= 8 || (protein + fat) >= 20) {
            return "SLOW";
        }
        if (gi >= 70 && fiber < 4) {
            return "FAST";
        }
        return "MEDIUM";
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
