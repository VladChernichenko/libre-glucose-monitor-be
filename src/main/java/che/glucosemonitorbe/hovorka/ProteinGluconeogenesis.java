package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Delayed glucose from protein (gluconeogenesis), modelled as a slow-carb equivalent entry at
 * {@code meal + ONSET_MIN}. A big protein portion raises glucose 1.5-3 h after the meal, after a
 * normal rapid bolus has mostly worn off - the late half of the "early low, late high" pattern.
 *
 * <p>Single source for both prediction paths: {@code /api/predict} (a prospective meal) and the
 * dashboard (logged notes), so the same steak predicts the same late rise on both screens.
 * Fat is deliberately not converted: its effect is gastric delay, already modelled by
 * {@link MacroNutrientGastricModel} on {@code /api/predict} and by the GLP-1 ileal brake in the
 * Hovorka solver; adding fat kcal here caused a +1.1 mmol/L meal-tail overshoot in the backtest.</p>
 */
public final class ProteinGluconeogenesis {

    /** Onset of the protein rise after the meal [min] (GNG peaks around 3-4 h). */
    public static final int    ONSET_MIN            = 120;
    /** Share of protein that is glucogenic. */
    public static final double GLUCOGENIC_FRACTION  = 0.50;
    /**
     * Grams of slow-carb-equivalent glucose per gram of protein (Warsaw Protocol adapted):
     * 4 kcal/g ÷ 100 kcal/FPU × 10 g carb/FPU = 0.40 g/g.
     */
    public static final double CARB_FACTOR          = 0.40;
    /** Minimum equivalent carbs [g] worth an entry (≈ 0.2 FPU). */
    public static final double MIN_EQUIV_G          = 2.0;
    /** {@code mealType} of the equivalent entry. */
    public static final String MEAL_TYPE            = "fpu-equiv";

    private ProteinGluconeogenesis() {}

    /** Slow-carb equivalent grams for {@code proteinG} at the given glucogenic fraction. */
    public static double equivalentCarbs(double proteinG, double glucogenicFraction) {
        return Math.max(0.0, proteinG) * glucogenicFraction * CARB_FACTOR;
    }

    /**
     * {@code entries} plus one delayed-glucose entry per meal whose protein clears
     * {@link #MIN_EQUIV_G}. The input list is not modified; the added entries carry no protein,
     * so applying this twice does not expand them again.
     */
    public static List<CarbsEntry> withDelayedProteinGlucose(List<CarbsEntry> entries) {
        List<CarbsEntry> out = new ArrayList<>(entries);
        for (CarbsEntry meal : entries) {
            if (meal.getTimestamp() == null || meal.getProtein() == null) continue;
            if (MEAL_TYPE.equals(meal.getMealType())) continue;
            double equiv = equivalentCarbs(meal.getProtein(), GLUCOGENIC_FRACTION);
            if (equiv < MIN_EQUIV_G) continue;
            CarbsEntry delayed = CarbsEntry.builder()
                    .id(UUID.randomUUID())
                    .timestamp(meal.getTimestamp().plusMinutes(ONSET_MIN))
                    .carbs(equiv)
                    .mealType(MEAL_TYPE)
                    .userId(meal.getUserId())
                    .build();
            // Inherit the meal's absorption kinetics. The Hovorka gut lets the newest carbs set the
            // GI / gut rate for everything still in it, so a bare entry (default GI 70, no fiber)
            // would switch a slow low-GI meal's remaining carbs to fast absorption at the onset and
            // dump them - a 12 g protein equivalent then raised the 4 h forecast by ~3 mmol/L.
            delayed.setEstimatedGi(meal.getEstimatedGi());
            delayed.setFiber(meal.getFiber());
            delayed.setAbsorptionSpeedClass(meal.getAbsorptionSpeedClass());
            out.add(delayed);
        }
        return out;
    }
}
