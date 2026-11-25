package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.domain.MealWindow;

/**
 * Learned insulin-to-carb ratio for one meal window.
 *
 * @param gramsPerUnit median grams per unit; {@code null} when there is no data
 * @param sampleCount  clean meals that gave an estimate in the window
 */
public record CarbRatioWindowEstimate(MealWindow window, Double gramsPerUnit, int sampleCount) {
}
