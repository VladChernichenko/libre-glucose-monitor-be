package che.glucosemonitorbe.hovorka;

/**
 * What one carb entry's absorption kernel depends on.
 *
 * @param gi       glycemic index (0-100); {@link HovorkaOdeSolver#DEFAULT_GI} when unknown
 * @param fiberG   the entry's fiber plus first-course fiber eaten up to 30 min before [g]
 * @param fatG     fat of this entry and of entries within ±30 min [g]
 * @param proteinG protein of this entry and of entries within ±30 min [g]
 * @param rescue   a hypo treatment: fixed fast kernel, macros and scales ignored
 */
public record MealInputs(double gi, double fiberG, double fatG, double proteinG, boolean rescue) {
}
