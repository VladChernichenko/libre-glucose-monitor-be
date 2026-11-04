package che.glucosemonitorbe.service.nutrition;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only place a food name becomes carbohydrate, fat, protein, fiber, glycemic index and
 * glycemic load. Grams scale the per-100 g catalog row. Glycemic load is
 * {@code GI ? max(0, carbs ? fiber) / 100} and is not stored in the catalog.
 */
@Service
@RequiredArgsConstructor
public class FoodCompositionService {

    private static final Pattern GRAMS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*g\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LETTERS = Pattern.compile("[\\p{L}]{3,}");

    private final FoodCatalog catalog;
    private final GlycemicPatternMatchingService patterns;

    public NutritionSnapshot compose(List<FoodPortion> items) {
        double carbs = 0, fiber = 0, protein = 0, fat = 0;
        double giWeighted = 0, giWeight = 0;
        boolean any = false;
        boolean estimated = false;
        List<String> names = new ArrayList<>();
        for (FoodPortion item : merge(items)) {
            names.add(shown(item));
            FoodCatalog.Match match = catalog.find(item.name());
            if (match == null) continue;
            any = true;
            double grams = item.grams() > 0 ? item.grams() : match.typicalServingG();
            boolean usedTypical = item.grams() <= 0 || item.massEstimated();
            estimated |= usedTypical;
            double scale = grams / 100.0;
            double itemCarbs = match.carbs100g() * scale;
            carbs += itemCarbs;
            fiber += match.fiber100g() * scale;
            protein += match.protein100g() * scale;
            fat += match.fat100g() * scale;
            if (match.gi() != null && itemCarbs > 0) {
                giWeighted += match.gi() * itemCarbs;
                giWeight += itemCarbs;
            }
        }
        Double gi = giWeight > 0 ? round1(giWeighted / giWeight) : null;
        Double gl = gi == null ? null : round1(gi * Math.max(0, carbs - fiber) / 100.0);
        NutritionSnapshot snapshot = NutritionSnapshot.builder()
                .absorptionMode(gi != null ? "GI_GL_ENHANCED" : "DEFAULT_DECAY")
                .source(any ? "FOOD_CATALOG" : "UNMATCHED")
                .confidence(any ? (estimated ? 0.45 : 0.75) : 0.2)
                .totalCarbs(round1(carbs))
                .fiber(round1(fiber))
                .protein(round1(protein))
                .fat(round1(fat))
                .estimatedGi(gi)
                .glycemicLoad(gl)
                .massEstimated(estimated)
                .normalizedFoods(names)
                .build();
        return patterns.enrich(snapshot);
    }

    /**
     * Text such as "rice 150 g" or a typed note. A logged carb gram number, with no portion in the
     * text, stays the carbohydrate total and only scales the catalog so the index still applies.
     * Text with no food name does not invent a meal.
     */
    public NutritionSnapshot composeFromText(String detailed, String comment, Double fallbackCarbs) {
        String text = ((detailed != null ? detailed : "") + " " + (comment != null ? comment : "")).trim();
        double loggedCarbs = fallbackCarbs != null ? Math.max(0, fallbackCarbs) : 0;
        if (!LETTERS.matcher(text).find()) {
            return manualCarbs(loggedCarbs);
        }
        List<FoodPortion> portions = new ArrayList<>();
        boolean anyGrams = false;
        for (String segment : text.split("(?i)\\s*(?:,|;|\\band\\b|\\bwith\\b)\\s*")) {
            String part = segment.trim();
            if (part.isBlank() || !LETTERS.matcher(part).find()) continue;
            Matcher grams = GRAMS.matcher(part);
            if (grams.find()) {
                anyGrams = true;
                double g = Double.parseDouble(grams.group(1));
                String name = grams.replaceAll(" ").replaceAll("\\s+", " ").trim();
                portions.add(new FoodPortion(name, g, false));
            } else {
                portions.add(new FoodPortion(part, 0, true));
            }
        }
        if (portions.isEmpty()) return manualCarbs(loggedCarbs);
        NutritionSnapshot snapshot = compose(portions);
        if (!anyGrams && loggedCarbs > 0 && snapshot.getTotalCarbs() != null && snapshot.getTotalCarbs() > 0) {
            double factor = loggedCarbs / snapshot.getTotalCarbs();
            snapshot.setTotalCarbs(round1(loggedCarbs));
            snapshot.setFiber(round1(nz(snapshot.getFiber()) * factor));
            snapshot.setProtein(round1(nz(snapshot.getProtein()) * factor));
            snapshot.setFat(round1(nz(snapshot.getFat()) * factor));
            if (snapshot.getEstimatedGi() != null) {
                double avail = Math.max(0, snapshot.getTotalCarbs() - nz(snapshot.getFiber()));
                snapshot.setGlycemicLoad(round1(snapshot.getEstimatedGi() * avail / 100.0));
            }
        }
        return snapshot;
    }

    private static List<FoodPortion> merge(List<FoodPortion> items) {
        Map<String, FoodPortion> byName = new LinkedHashMap<>();
        if (items == null) return List.of();
        for (FoodPortion item : items) {
            if (item == null || item.name() == null || item.name().isBlank()) continue;
            String key = item.name().trim().toLowerCase(Locale.ROOT);
            FoodPortion prior = byName.get(key);
            if (prior == null) {
                byName.put(key, new FoodPortion(item.name().trim(), Math.max(0, item.grams()), item.massEstimated()));
            } else {
                byName.put(key, new FoodPortion(prior.name(), prior.grams() + Math.max(0, item.grams()),
                        prior.massEstimated() || item.massEstimated()));
            }
        }
        return List.copyOf(byName.values());
    }

    private static String shown(FoodPortion item) {
        if (item.grams() <= 0) return item.name();
        double rounded = Math.round(item.grams() * 10.0) / 10.0;
        String grams = rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
        return item.name() + " " + grams + " g";
    }

    private static NutritionSnapshot manualCarbs(double carbs) {
        return NutritionSnapshot.builder()
                .absorptionMode("DEFAULT_DECAY")
                .source("MANUAL_CARBS")
                .confidence(1.0)
                .totalCarbs(round1(carbs))
                .fiber(0.0)
                .protein(0.0)
                .fat(0.0)
                .estimatedGi(null)
                .glycemicLoad(null)
                .massEstimated(false)
                .normalizedFoods(List.of())
                .build();
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
