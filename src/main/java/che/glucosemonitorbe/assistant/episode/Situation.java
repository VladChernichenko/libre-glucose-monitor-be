package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.assistant.mealplan.MealMacros;

/**
 * The situation a similar-episode search is run for. Glucose in mmol/L, IOB in units,
 * {@code activityMinutes} = activity minutes around the meal.
 */
public record Situation(String foodText, MealMacros macros, int minuteOfDay, double glucose, double iob,
                        int activityMinutes) {
}
