package che.glucosemonitorbe.service.nutrition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NutritionEnrichmentService {
    private static final Pattern CARB_GRAM_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*g\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LETTER_PATTERN = Pattern.compile("[a-zA-Z]{3,}");
    private static final double DEFAULT_GI = 55.0;

    private final NutritionApiNinjaService nutritionApiNinjaService;

    public NutritionSnapshot enrichFromText(String detailedInput, String comment, Double fallbackCarbs) {
        String text = ((detailedInput != null ? detailedInput : "") + " " + (comment != null ? comment : "")).trim();
        double carbs = fallbackCarbs != null ? Math.max(0.0, fallbackCarbs) : 0.0;
        boolean hasFoodEntities = hasFoodEntities(text);

        // Explicit fallback for input like "20g" without recognizable foods.
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

        List<Map<String, Object>> apiRows = nutritionApiNinjaService.lookupFoods(text);
        if (apiRows.isEmpty()) {
            log.info("Nutrition enrichment: no Spoonacular rows, using MANUAL_CARBS (textLength={})", text.length());
            return NutritionSnapshot.builder()
                    .absorptionMode("DEFAULT_DECAY")
                    .source("MANUAL_CARBS")
                    .confidence(0.4)
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

        double totalCarbs = 0.0;
        double totalFiber = 0.0;
        double totalProtein = 0.0;
        double totalFat = 0.0;
        List<String> foods = new ArrayList<>();
        for (Map<String, Object> row : apiRows) {
            totalCarbs += toDouble(row.get("carbohydrates_total_g"));
            totalFiber += toDouble(row.get("fiber_g"));
            totalProtein += toDouble(row.get("protein_g"));
            totalFat += toDouble(row.get("fat_total_g"));
            Object name = row.get("name");
            if (name != null) {
                foods.add(name.toString().toLowerCase(Locale.ROOT));
            }
        }
        if (totalCarbs <= 0.0) {
            totalCarbs = carbs;
        }
        double estimatedGi = estimateGiFromFoods(foods);
        double availableCarbs = Math.max(0.0, totalCarbs - totalFiber);
        double gl = (estimatedGi * availableCarbs) / 100.0;

        log.info(
                "Nutrition enrichment: SPOONACULAR rows={} foods={} totalCarbs={} estimatedGi={} gl={}",
                apiRows.size(),
                foods.size(),
                round1(totalCarbs),
                round1(estimatedGi),
                round1(gl));

        return NutritionSnapshot.builder()
                .absorptionMode("GI_GL_ENHANCED")
                .source("SPOONACULAR")
                .confidence(0.85)
                .totalCarbs(round1(totalCarbs))
                .fiber(round1(totalFiber))
                .protein(round1(totalProtein))
                .fat(round1(totalFat))
                .estimatedGi(round1(estimatedGi))
                .glycemicLoad(round1(gl))
                .absorptionSpeedClass(classify(estimatedGi, totalFiber, totalProtein, totalFat))
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
            if (food.contains("white bread") || food.contains("rice") || food.contains("potato")) {
                sum += 75.0;
            } else if (food.contains("milk") || food.contains("apple") || food.contains("orange")) {
                sum += 35.0;
            } else if (food.contains("rye")) {
                sum += 50.0;
            } else {
                sum += DEFAULT_GI;
            }
        }
        return sum / foods.size();
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

    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
