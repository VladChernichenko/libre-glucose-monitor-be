package che.glucosemonitorbe.assistant.mealplan;

/** A macro estimate as low/mid/high grams; the band widens as the nutrition confidence falls. */
public record NutritionRange(double low, double mid, double high) {

    static final double MIN_WIDTH = 0.10;
    static final double MAX_WIDTH = 0.40;

    /** w = clamp(0.5 x (1 - confidence), 0.10, 0.40); null confidence = widest band (0.40). */
    public static NutritionRange of(double mid, Double confidence) {
        double w = confidence == null ? MAX_WIDTH
                : Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, 0.5 * (1.0 - confidence)));
        return new NutritionRange(mid * (1 - w), mid, mid * (1 + w));
    }
}
