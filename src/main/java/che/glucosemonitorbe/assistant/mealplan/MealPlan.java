package che.glucosemonitorbe.assistant.mealplan;

/**
 * A meal card: nutrition ranges always; dose and pause only with a fresh CGM reading and an
 * accepted calculation.
 *
 * @param refusalCode {@code DosingRefusalReason} name when the calculator refused, else null
 * @param stale       true when no fresh CGM reading exists (then dose, refusalCode and pause are null)
 * @param history     the history-dose banner (spec 6.6) when it should show, else null
 */
public record MealPlan(NutritionRange carbs, NutritionRange fat, NutritionRange protein, NutritionRange fiber,
                       Double gi, String patternLabel, Double doseUnits, String refusalCode, PauseChoice pause,
                       boolean stale, HistoryDose history) {

    /** A plan without a history banner. */
    public MealPlan(NutritionRange carbs, NutritionRange fat, NutritionRange protein, NutritionRange fiber,
                    Double gi, String patternLabel, Double doseUnits, String refusalCode, PauseChoice pause,
                    boolean stale) {
        this(carbs, fat, protein, fiber, gi, patternLabel, doseUnits, refusalCode, pause, stale, null);
    }
}
