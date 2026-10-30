package che.glucosemonitorbe.assistant.mealplan;

/**
 * The meal a pause is chosen for, in grams; {@code gi} is optional (null = the model's default GI).
 */
public record MealMacros(double carbs, double fat, double protein, double fiber, Double gi) {
}
