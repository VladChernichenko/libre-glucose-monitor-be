package che.glucosemonitorbe.service.nutrition;

/**
 * One recognised food. {@code grams} is the portion. {@code massEstimated} is true when the grams
 * were not stated by the person (a typical serving or a photo estimate).
 */
public record FoodPortion(String name, double grams, boolean massEstimated) {

    public FoodPortion(String name, double grams) {
        this(name, grams, false);
    }
}
