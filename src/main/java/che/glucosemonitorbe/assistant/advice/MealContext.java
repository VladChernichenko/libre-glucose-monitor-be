package che.glucosemonitorbe.assistant.advice;

import che.glucosemonitorbe.entity.Note;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Whether a meal is being absorbed at {@code now}, from the recent notes: a non-rescue carb note of
 * at least {@link #MIN_MEAL_CARBS_G} in the last {@link #MEAL_WINDOW}, or a rapid dose not labelled
 * "Correction" in the last {@link #PRE_BOLUS_WINDOW} with no carbs logged since (a pre-bolus waiting
 * for its meal).
 *
 * <p>In meal context the forecast's timing is least reliable (it can show a low the CGM never
 * confirms), so {@link CarbAdviceTrigger} asks for a CGM-confirmed fall before advising carbs.</p>
 */
public final class MealContext {

    public static final Duration MEAL_WINDOW = Duration.ofHours(4);
    public static final double MIN_MEAL_CARBS_G = 10.0;
    public static final Duration PRE_BOLUS_WINDOW = Duration.ofMinutes(60);

    private static final String CORRECTION_LABEL = "Correction";

    private MealContext() {}

    /** Notes after {@code now} are ignored. */
    public static boolean at(LocalDateTime now, List<Note> recentNotes) {
        if (now == null || recentNotes == null) return false;
        List<Note> past = recentNotes.stream()
                .filter(n -> n.getTimestamp() != null && !n.getTimestamp().isAfter(now))
                .toList();
        LocalDateTime mealSince = now.minus(MEAL_WINDOW);
        boolean meal = past.stream().anyMatch(n -> n.getTimestamp().isAfter(mealSince)
                && carbs(n) >= MIN_MEAL_CARBS_G && !n.isHypoTreatment());
        if (meal) return true;

        LocalDateTime doseSince = now.minus(PRE_BOLUS_WINDOW);
        for (Note dose : past) {
            if (!dose.getTimestamp().isAfter(doseSince) || !isRapid(dose)) continue;
            if (CORRECTION_LABEL.equalsIgnoreCase(dose.getMeal())) continue;
            boolean carbsSince = past.stream().anyMatch(n -> carbs(n) > 0
                    && !n.getTimestamp().isBefore(dose.getTimestamp()));
            if (!carbsSince) return true;
        }
        return false;
    }

    private static boolean isRapid(Note n) {
        return n.getInsulin() != null && n.getInsulin() > 0 && !n.isLongActing();
    }

    private static double carbs(Note n) {
        return n.getCarbs() == null ? 0.0 : n.getCarbs();
    }
}
