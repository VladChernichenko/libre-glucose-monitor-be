package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.assistant.chat.IntentClassifier;
import che.glucosemonitorbe.dto.ARFoodItem;
import che.glucosemonitorbe.dto.ARNutritionRequest;
import che.glucosemonitorbe.service.nutrition.ARNutritionService;
import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import che.glucosemonitorbe.service.nutrition.NutritionVisionService;
import che.glucosemonitorbe.storage.NotePhotoStorageService;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Meal-prep nutrition from the existing {@code service/nutrition}: a stored chat photo goes to
 * {@link NutritionVisionService}; named items go to {@link ARNutritionService} (fresh table,
 * OpenFoodFacts, keyword fallback per item, then the population pattern). An item without a stated
 * weight is sized at {@link #DEFAULT_PORTION_G} with low confidence, so the card's range is wide.
 * Empty when nothing usable was recognised.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealNutritionResolver {

    static final double DEFAULT_PORTION_G = 150.0;
    /** Oat words a person types for oats or porridge; with a small stated weight they mean dry oats. */
    static final java.util.Set<String> OAT_WORDS = java.util.Set.of("porridge", "oats", "oatmeal", "hercules",
            "овсянка", "овсяные хлопья", "геркулес", "каша овсяная", "овсяная каша");
    /** Up to this weight an oat item is the dry portion (a cooked bowl weighs 200 g or more). */
    static final double DRY_OATS_MAX_G = 120.0;
    /** Per-item confidence passed as the segmentation confidence: stated weight vs. assumed portion. */
    static final double STATED_WEIGHT_CONFIDENCE = 0.9;
    static final double ASSUMED_PORTION_CONFIDENCE = 0.3;

    private final NotePhotoStorageService photoStorage;
    private final NutritionVisionService vision;
    private final ObjectProvider<ARNutritionService> itemNutrition; // absent when Mongo/OFF is disabled
    private final NutritionEnrichmentService enrichment;

    public Optional<NutritionSnapshot> resolve(String photoKey, List<IntentClassifier.FoodItem> items) {
        NutritionSnapshot n;
        if (photoKey != null) {
            n = fromPhoto(photoKey);
        } else if (items != null && !items.isEmpty()) {
            n = fromItems(items);
        } else {
            return Optional.empty();
        }
        if (n == null || nothing(n)) return Optional.empty();
        return Optional.of(n);
    }

    private NutritionSnapshot fromPhoto(String photoKey) {
        try {
            NotePhotoStorageService.PhotoObject photo = photoStorage.download(photoKey);
            return vision.analyzeImage(photo.data(), photo.contentType());
        } catch (RuntimeException e) {
            log.warn("Meal-prep photo {} could not be analysed: {}", photoKey, e.getMessage());
            return null;
        }
    }

    private NutritionSnapshot fromItems(List<IntentClassifier.FoodItem> items) {
        ARNutritionService service = itemNutrition.getIfAvailable();
        boolean anyAssumed = items.stream().anyMatch(i -> i.grams() == null);
        if (service == null) {
            // No item database: keyword estimate with carbs at 15 % of the (assumed) mass, as AR's fallback.
            double mass = items.stream().mapToDouble(i -> i.grams() != null ? i.grams() : DEFAULT_PORTION_G).sum();
            String text = items.stream().map(IntentClassifier.FoodItem::name).collect(Collectors.joining(", "));
            NutritionSnapshot n = enrichment.enrichFromText(text, "", mass * 0.15);
            n.setConfidence(Math.min(n.getConfidence() != null ? n.getConfidence() : 0.4, ASSUMED_PORTION_CONFIDENCE));
            return n;
        }
        ARNutritionRequest req = new ARNutritionRequest();
        req.setFoods(items.stream().map(i -> {
            ARFoodItem f = new ARFoodItem();
            f.setLabel(label(i));
            f.setMassG(i.grams() != null ? i.grams() : DEFAULT_PORTION_G);
            f.setSegmentationConfidence(i.grams() != null ? STATED_WEIGHT_CONFIDENCE : ASSUMED_PORTION_CONFIDENCE);
            return f;
        }).toList());
        NutritionSnapshot n = service.analyze(req);
        if (n != null && anyAssumed && n.getConfidence() != null) {
            n.setConfidence(Math.min(n.getConfidence(), ASSUMED_PORTION_CONFIDENCE));
        }
        return n;
    }

    /** "60 g porridge" in chat is the dry oats weighed out, not 60 g of cooked porridge. */
    static String label(IntentClassifier.FoodItem i) {
        String name = i.name() == null ? "" : i.name().trim().toLowerCase(java.util.Locale.ROOT);
        boolean oats = OAT_WORDS.contains(name) || OAT_WORDS.stream().anyMatch(w -> name.endsWith(" " + w));
        return oats && i.grams() != null && i.grams() <= DRY_OATS_MAX_G ? ARNutritionService.DRY_OATS : i.name();
    }

    private static boolean nothing(NutritionSnapshot n) {
        return v(n.getTotalCarbs()) <= 0 && v(n.getFat()) <= 0 && v(n.getProtein()) <= 0;
    }

    private static double v(Double d) {
        return d == null || !Double.isFinite(d) ? 0.0 : d;
    }
}
