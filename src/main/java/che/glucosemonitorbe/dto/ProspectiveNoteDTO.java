package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a meal/insulin event that the user has not yet saved to notes.
 * Included in GlucoseCalculationsRequest so the prediction pipeline can factor in
 * a prospective (in-progress) meal without requiring persistence first.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProspectiveNoteDTO {

    /** Grams of carbohydrates in the prospective meal. */
    private Double carbs;

    /** Units of insulin co-administered with the meal. 0 or null = none yet. */
    private Double insulin;

    /** Meal label (e.g. "Lunch", "Dinner"). Used only for pattern matching context. */
    private String meal;

    /**
     * Serialised NutritionSnapshot JSON — same format stored in Note.nutritionProfile.
     * Provides GI, GL, fiber, fat, protein, absorption class, bolus strategy, etc.
     * Null is allowed; the entry will then use DEFAULT_DECAY absorption.
     */
    private String nutritionProfileJson;

    /**
     * How many minutes ago the meal started.
     * 0 (default) = right now (the user is about to eat or just started).
     */
    @Builder.Default
    private Integer minutesAgo = 0;
}
