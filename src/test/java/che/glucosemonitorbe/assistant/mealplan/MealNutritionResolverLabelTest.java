package che.glucosemonitorbe.assistant.mealplan;

import static org.junit.jupiter.api.Assertions.*;

import che.glucosemonitorbe.assistant.chat.IntentClassifier.FoodItem;
import che.glucosemonitorbe.service.nutrition.ARNutritionService;
import org.junit.jupiter.api.Test;

class MealNutritionResolverLabelTest {

    @Test
    void aSmallWeighedOatPortionIsDryOats() {
        assertEquals(ARNutritionService.DRY_OATS, MealNutritionResolver.label(new FoodItem("porridge", 60.0)));
        assertEquals(ARNutritionService.DRY_OATS, MealNutritionResolver.label(new FoodItem("Oatmeal", 35.0)));
        assertEquals(ARNutritionService.DRY_OATS, MealNutritionResolver.label(new FoodItem("овсянка", 50.0)));
    }

    @Test
    void aCookedBowlOrAnUnweighedOrOtherFoodKeepsItsName() {
        assertEquals("porridge", MealNutritionResolver.label(new FoodItem("porridge", 300.0)));
        assertEquals("porridge", MealNutritionResolver.label(new FoodItem("porridge", null)));
        assertEquals("milk", MealNutritionResolver.label(new FoodItem("milk", 60.0)));
    }
}
