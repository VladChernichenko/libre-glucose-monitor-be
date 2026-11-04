package che.glucosemonitorbe.assistant.mealplan;

import static org.junit.jupiter.api.Assertions.*;

import che.glucosemonitorbe.assistant.chat.IntentClassifier.FoodItem;
import org.junit.jupiter.api.Test;

class MealNutritionResolverLabelTest {

    @Test
    void aSmallWeighedOatPortionIsDryOats() {
        assertEquals(MealNutritionResolver.DRY_OATS, MealNutritionResolver.label(new FoodItem("porridge", 60.0)));
        assertEquals(MealNutritionResolver.DRY_OATS, MealNutritionResolver.label(new FoodItem("Oatmeal", 35.0)));
        assertEquals(MealNutritionResolver.DRY_OATS, MealNutritionResolver.label(new FoodItem("\u043e\u0432\u0441\u044f\u043d\u043a\u0430", 50.0)));
    }

    @Test
    void aCookedBowlOrAnUnweighedOrOtherFoodKeepsItsName() {
        assertEquals("porridge", MealNutritionResolver.label(new FoodItem("porridge", 300.0)));
        assertEquals("porridge", MealNutritionResolver.label(new FoodItem("porridge", null)));
        assertEquals("milk", MealNutritionResolver.label(new FoodItem("milk", 60.0)));
    }
}
