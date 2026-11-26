package che.glucosemonitorbe.hovorka;

import java.util.ArrayList;
import java.util.List;

/**
 * Glucose appearance from meals, each spread over time by its own {@link GammaKernel}.
 *
 * <p>Same step convention and output as {@link MealGutChains}: a meal added with step {@code s}
 * enters before step {@code s}, i.e. is eaten at minute {@code e = s − 1} relative to "now"
 * (negative for a past meal). The appearance in forecast step {@code m} is
 * {@code mass × (cdf(m − e) − cdf(m − 1 − e))}, so a past meal contributes only what is left and
 * no warm-up replay is needed. Meals do not interact.</p>
 */
public final class GammaMealAppearance {

    private record Meal(int eatenAt, double massMmol, GammaKernel kernel) {}

    private final List<Meal> meals = new ArrayList<>();

    /** A meal of {@code massMmol} glucose entering before step {@code step}. */
    public void addMeal(int step, double massMmol, GammaKernel kernel) {
        if (massMmol > 0 && kernel != null) meals.add(new Meal(step - 1, massMmol, kernel));
    }

    public MealGutChains.Appearance appearance(int pathMinutes) {
        double[] stepMean = new double[pathMinutes + 1];
        double[] atEnd = new double[pathMinutes + 1];
        for (Meal meal : meals) {
            double prev = meal.kernel().cdf(-meal.eatenAt());
            for (int m = 1; m <= pathMinutes; m++) {
                double t = m - meal.eatenAt();
                double cdf = meal.kernel().cdf(t);
                stepMean[m] += meal.massMmol() * (cdf - prev);
                atEnd[m] += meal.massMmol() * meal.kernel().pdf(t);
                prev = cdf;
            }
        }
        return new MealGutChains.Appearance(stepMean, atEnd);
    }
}
