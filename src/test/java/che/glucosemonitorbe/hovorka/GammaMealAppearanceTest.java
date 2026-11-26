package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GammaMealAppearanceTest {

    private static final GammaKernel K = GammaKernel.fromModeAndSd(48, 53);
    private static final GammaKernel FAST = GammaKernel.fromModeAndSd(15, 10);

    @Test
    void appearanceIsAdditiveAcrossMeals() {
        GammaMealAppearance a = new GammaMealAppearance();
        a.addMeal(1, 100, K);
        GammaMealAppearance b = new GammaMealAppearance();
        b.addMeal(30, 50, FAST);
        GammaMealAppearance both = new GammaMealAppearance();
        both.addMeal(1, 100, K);
        both.addMeal(30, 50, FAST);
        double[] sa = a.appearance(240).stepMean(), sb = b.appearance(240).stepMean();
        double[] s = both.appearance(240).stepMean();
        for (int m = 1; m <= 240; m++) assertThat(s[m]).isCloseTo(sa[m] + sb[m], within(1e-12));
    }

    @Test
    void pastMealCountsOnlyWhatIsLeft() {
        GammaMealAppearance a = new GammaMealAppearance();
        a.addMeal(1 - 60, 100, K);   // eaten 60 min ago
        double sum = 0;
        for (double v : a.appearance(2000).stepMean()) sum += v;
        assertThat(sum).isCloseTo(100 * (1 - K.cdf(60)), within(1e-6));
    }

    @Test
    void prospectiveMealStartsAtItsStep() {
        GammaMealAppearance a = new GammaMealAppearance();
        a.addMeal(30, 100, K);
        double[] s = a.appearance(240).stepMean();
        for (int m = 1; m < 30; m++) assertThat(s[m]).isZero();
        assertThat(s[31]).isPositive();
    }
}
