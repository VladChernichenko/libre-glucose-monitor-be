package che.glucosemonitorbe.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent description of one real-life meal episode for {@link MealEpisodeE2ETest}.
 * All minutes are relative to the FIRST BITE of the main meal (t0); glucose is mmol/L.
 */
final class MealEpisode {

    /** Meal macros. gi = glycaemic index; grams for fiber/protein/fat; speed FAST|MEDIUM|SLOW|null. */
    record Nutrition(double gi, double fiber, double protein, double fat, String speedClass) {}

    /** A note logged before t0. {@code firstCourse} marks a "vegetables / protein first" course. */
    record HistoryNote(int minutesBeforeMeal, double carbs, double insulin, String meal,
                       String type, Nutrition nutrition, boolean firstCourse) {}

    final String name;
    String mealTime = "13:00";
    Double startGlucose;
    Double trendMmolPerMin;
    final Map<String, Object> settings = new LinkedHashMap<>();
    final Map<String, Object> insulinPreferences = new LinkedHashMap<>();
    final List<HistoryNote> history = new ArrayList<>();
    Double expectedIob, expectedCob;
    double iobTolerance = 0.5, cobTolerance = 10;
    String mealDescription = "Meal";
    double mealCarbs;
    Nutrition mealNutrition;
    double bolusUnits;
    int preBolusMinutes;
    /** Non-null = dose comes from the backend dose calculator with this target BG (mmol/L). */
    Double calculatorTargetGlucose;
    /** Split bolus: second injection {@code secondBolusMinutesAfterMeal} after t0 (0 = none). */
    double secondBolusUnits;
    int secondBolusMinutesAfterMeal;
    Double actual2h, actual4h;
    double toleranceMmol = 2.0;

    MealEpisode(String name) { this.name = name; }

    MealEpisode mealTime(String hhmm)             { mealTime = hhmm; return this; }
    MealEpisode startGlucose(double mmol)         { startGlucose = mmol; return this; }
    MealEpisode trendMmolPerMin(Double v)         { trendMmolPerMin = v; return this; }
    MealEpisode setting(String field, Object v)   { settings.put(field, v); return this; }
    MealEpisode isf(double v)                     { return setting("isf", v); }
    MealEpisode carbRatio(double v)               { return setting("carbRatio", v); }
    MealEpisode bodyWeightKg(double v)            { return setting("bodyWeightKg", v); }
    MealEpisode carbHalfLife(int minutes)         { return setting("carbHalfLife", minutes); }
    MealEpisode maxCobDuration(int minutes)       { return setting("maxCOBDuration", minutes); }
    /** Meal-window ISF overrides (mmol/L per U); null = use {@link #isf}. */
    MealEpisode isfBreakfast(Double v)            { return setting("isfBreakfast", v); }
    MealEpisode isfLunch(Double v)                { return setting("isfLunch", v); }
    MealEpisode isfDinner(Double v)               { return setting("isfDinner", v); }
    MealEpisode isfNight(Double v)                { return setting("isfNight", v); }

    MealEpisode insulin(String rapidCode, String longActingCode, String longActingInjectionTime) {
        insulinPreferences.put("rapidInsulinCode", rapidCode);
        insulinPreferences.put("longActingInsulinCode", longActingCode);
        insulinPreferences.put("longActingInjectionTime", longActingInjectionTime);
        return this;
    }

    MealEpisode historyMeal(int minutesBeforeMeal, double carbs, Nutrition nutrition) {
        history.add(new HistoryNote(minutesBeforeMeal, carbs, 0, "Snack", null, nutrition, false));
        return this;
    }

    MealEpisode historyBolus(int minutesBeforeMeal, double units) {
        history.add(new HistoryNote(minutesBeforeMeal, 0, units, "Bolus", null, null, false));
        return this;
    }

    MealEpisode historyLongActing(int minutesBeforeMeal, double units) {
        history.add(new HistoryNote(minutesBeforeMeal, 0, units, "Basal", "long_acting", null, false));
        return this;
    }

    /**
     * "Vegetables / protein first" course eaten {@code minutesBeforeMeal} before the main
     * carbs (e.g. a salad 10 min before the pasta). Logged as its own note with its macros.
     */
    MealEpisode firstCourse(int minutesBeforeMeal, String description, double carbs, Nutrition nutrition) {
        history.add(new HistoryNote(minutesBeforeMeal, carbs, 0, description, null, nutrition, true));
        return this;
    }

    MealEpisode expectedAtStart(Double iobUnits, Double cobGrams) {
        expectedIob = iobUnits;
        expectedCob = cobGrams;
        return this;
    }

    MealEpisode meal(String description, double carbs, Nutrition nutrition) {
        mealDescription = description;
        mealCarbs = carbs;
        mealNutrition = nutrition;
        return this;
    }

    MealEpisode bolus(double units, int preBolusMinutes) {
        bolusUnits = units;
        this.preBolusMinutes = preBolusMinutes;
        calculatorTargetGlucose = null;
        return this;
    }

    /** Dose = app calculator's recommendation for this meal, BG, starting IOB and target BG. */
    MealEpisode bolusFromCalculator(double targetGlucose, int preBolusMinutes) {
        calculatorTargetGlucose = targetGlucose;
        this.preBolusMinutes = preBolusMinutes;
        return this;
    }

    /**
     * Split bolus (pen equivalent of dual-wave): a second injection {@code minutesAfterMeal}
     * after the first bite, e.g. for the delayed protein/fat rise. It is sent to the prediction
     * as a planned (prospective) dose, since at t0 it has not been injected yet.
     */
    MealEpisode secondBolus(double units, int minutesAfterMeal) {
        secondBolusUnits = units;
        secondBolusMinutesAfterMeal = minutesAfterMeal;
        return this;
    }

    MealEpisode actual(Double glucose2h, Double glucose4h) {
        actual2h = glucose2h;
        actual4h = glucose4h;
        return this;
    }

    MealEpisode toleranceMmol(double v)           { toleranceMmol = v; return this; }
}
