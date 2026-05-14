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
        double estimatedGi = estimateGiFromFoods(foods);
        double availableCarbs = Math.max(0.0, carbs);
        double gl = (estimatedGi * availableCarbs) / 100.0;

        log.info("Nutrition enrichment: keyword GI estimate foods={} gi={} gl={}", foods, round1(estimatedGi), round1(gl));

        return NutritionSnapshot.builder()
                .absorptionMode("GI_GL_ENHANCED")
                .source("KEYWORD_GI")
                .confidence(0.4)
                .totalCarbs(carbs)
                .fiber(0.0)
                .protein(0.0)
                .fat(0.0)
                .estimatedGi(round1(estimatedGi))
                .glycemicLoad(round1(gl))
                .absorptionSpeedClass(classify(estimatedGi, 0, 0, 0))
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
