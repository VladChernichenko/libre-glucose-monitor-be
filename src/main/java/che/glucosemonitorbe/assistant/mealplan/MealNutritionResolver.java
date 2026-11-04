package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.assistant.chat.IntentClassifier;
import che.glucosemonitorbe.service.nutrition.FoodCompositionService;
import che.glucosemonitorbe.service.nutrition.FoodPortion;
import che.glucosemonitorbe.service.nutrition.MealPhotoRecognizer;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import che.glucosemonitorbe.storage.NotePhotoStorageService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Meal-prep nutrition. A stored chat photo is named by {@link MealPhotoRecognizer} and filled from
 * {@link FoodCompositionService}. Named items go to the same catalog. An item without a stated
 * weight uses the catalog's typical serving at low confidence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealNutritionResolver {

    static final double DEFAULT_PORTION_G = 150.0;
    /** Catalog name for the dry rolled-oats row. */
    static final String DRY_OATS = "rolled oats (dry)";
    /** Oat words a person types for oats or porridge; with a small stated weight they mean dry oats. */
    static final java.util.Set<String> OAT_WORDS = java.util.Set.of("porridge", "oats", "oatmeal", "hercules",
            "\u043e\u0432\u0441\u044f\u043d\u043a\u0430", "\u043e\u0432\u0441\u044f\u043d\u044b\u0435 \u0445\u043b\u043e\u043f\u044c\u044f",
            "\u0433\u0435\u0440\u043a\u0443\u043b\u0435\u0441", "\u043a\u0430\u0448\u0430 \u043e\u0432\u0441\u044f\u043d\u0430\u044f",
            "\u043e\u0432\u0441\u044f\u043d\u0430\u044f \u043a\u0430\u0448\u0430");
    /** Up to this weight an oat item is the dry portion (a cooked bowl weighs 200 g or more). */
    static final double DRY_OATS_MAX_G = 120.0;
    static final double ASSUMED_PORTION_CONFIDENCE = 0.3;

    private final NotePhotoStorageService photoStorage;
    private final MealPhotoRecognizer recognizer;
    private final FoodCompositionService composition;

    public Optional<NutritionSnapshot> resolve(String photoKey, List<IntentClassifier.FoodItem> items) {
        return resolve(photoKey, null, null, items);
    }

    /**
     * {@code photoBytes} is the picture kept on the phone when object storage is off. A storage key
     * is used only when the photo was uploaded.
     */
    public Optional<NutritionSnapshot> resolve(String photoKey, byte[] photoBytes, String contentType,
                                               List<IntentClassifier.FoodItem> items) {
        NutritionSnapshot n;
        if (photoBytes != null && photoBytes.length > 0) {
            n = fromBytes(photoBytes, contentType);
        } else if (photoKey != null) {
            n = fromPhoto(photoKey);
        } else if (items != null && !items.isEmpty()) {
            n = fromItems(items);
        } else {
            return Optional.empty();
        }
        if (n == null || nothing(n)) return Optional.empty();
        return Optional.of(n);
    }

    private NutritionSnapshot fromBytes(byte[] data, String contentType) {
        try {
            return composition.compose(recognizer.recognize(data, contentType == null ? "image/jpeg" : contentType));
        } catch (RuntimeException e) {
            log.warn("Meal-prep photo could not be analysed: {}", e.getMessage());
            return null;
        }
    }

    private NutritionSnapshot fromPhoto(String photoKey) {
        try {
            NotePhotoStorageService.PhotoObject photo = photoStorage.download(photoKey);
            return composition.compose(recognizer.recognize(photo.data(), photo.contentType()));
        } catch (RuntimeException e) {
            log.warn("Meal-prep photo {} could not be analysed: {}", photoKey, e.getMessage());
            return null;
        }
    }

    private NutritionSnapshot fromItems(List<IntentClassifier.FoodItem> items) {
        boolean anyAssumed = items.stream().anyMatch(i -> i.grams() == null);
        List<FoodPortion> portions = items.stream().map(i -> {
            boolean assumed = i.grams() == null;
            double grams = assumed ? DEFAULT_PORTION_G : i.grams();
            return new FoodPortion(label(i), grams, assumed);
        }).toList();
        NutritionSnapshot n = composition.compose(portions);
        if (n != null && anyAssumed && n.getConfidence() != null) {
            n.setConfidence(Math.min(n.getConfidence(), ASSUMED_PORTION_CONFIDENCE));
        }
        return n;
    }

    /** "60 g porridge" in chat is the dry oats weighed out, not 60 g of cooked porridge. */
    static String label(IntentClassifier.FoodItem i) {
        String name = i.name() == null ? "" : i.name().trim().toLowerCase(java.util.Locale.ROOT);
        boolean oats = OAT_WORDS.contains(name) || OAT_WORDS.stream().anyMatch(w -> name.endsWith(" " + w));
        return oats && i.grams() != null && i.grams() <= DRY_OATS_MAX_G ? DRY_OATS : i.name();
    }

    private static boolean nothing(NutritionSnapshot n) {
        boolean noMacros = v(n.getTotalCarbs()) <= 0 && v(n.getFat()) <= 0 && v(n.getProtein()) <= 0;
        boolean noNames = n.getNormalizedFoods() == null || n.getNormalizedFoods().isEmpty();
        return noMacros && noNames;
    }

    private static double v(Double d) {
        return d == null || !Double.isFinite(d) ? 0.0 : d;
    }
}
