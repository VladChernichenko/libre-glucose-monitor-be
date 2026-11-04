package che.glucosemonitorbe.service.nutrition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MealPhotoRecognizerTest {

    private final MealPhotoRecognizer recognizer =
            new MealPhotoRecognizer(null, new ObjectMapper());

    @Test
    void parseKeepsNamesAndGramsAndDropsNothingElse() {
        List<FoodPortion> foods = recognizer.parse("""
                {"foods":[{"name":"juice","grams":180},{"name":"","grams":10},{"name":"bread","grams":0}]}
                """);
        assertEquals(1, foods.size());
        assertEquals("juice", foods.get(0).name());
        assertEquals(180, foods.get(0).grams(), 0.01);
        assertTrue(foods.get(0).massEstimated());
    }

    @Test
    void parseStripsAMarkdownFence() {
        List<FoodPortion> foods = recognizer.parse("""
                ```json
                {"foods":[{"name":"banana","grams":120}]}
                ```
                """);
        assertEquals("banana", foods.get(0).name());
        assertEquals(120, foods.get(0).grams(), 0.01);
    }
}
