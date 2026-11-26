package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GammaKernelParamsTest {

    private static MealInputs plain(double gi) {
        return new MealInputs(gi, 0, 0, 0, false);
    }

    @Test
    void defaultMealIs95PercentIn4h() {
        GammaKernel g = GammaKernelParams.POPULATION.kernelFor(plain(70), 1, 1);
        assertThat(g.cdf(240)).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    void gi30PeaksAbout30MinLaterAnd40PercentWiderThanGi100() {
        GammaKernel slow = GammaKernelParams.PRIOR.kernelFor(plain(30), 1, 1);
        GammaKernel fast = GammaKernelParams.PRIOR.kernelFor(plain(100), 1, 1);
        assertThat(slow.mode() - fast.mode()).isCloseTo(30, within(2.0));
        assertThat(slow.sd() / fast.sd()).isCloseTo(1.40, within(0.03));
    }

    @Test
    void fat45gDelaysPeak60To90Min() {
        GammaKernel lean = GammaKernelParams.PRIOR.kernelFor(plain(70), 1, 1);
        GammaKernel fatty = GammaKernelParams.PRIOR.kernelFor(new MealInputs(70, 0, 45, 0, false), 1, 1);
        assertThat(fatty.mode() - lean.mode()).isBetween(60.0, 90.0);
    }

    @Test
    void scalesMultiplyModeAndSd() {
        GammaKernel base = GammaKernelParams.PRIOR.kernelFor(plain(70), 1, 1);
        GammaKernel scaled = GammaKernelParams.PRIOR.kernelFor(plain(70), 2.0, 1.5);
        assertThat(scaled.mode()).isCloseTo(base.mode() * 2.0, within(1e-6));
        assertThat(scaled.sd()).isCloseTo(base.sd() * 1.5, within(1e-6));
    }

    @Test
    void rescueIgnoresMacrosAndScales() {
        GammaKernel a = GammaKernelParams.PRIOR.kernelFor(new MealInputs(20, 10, 50, 50, true), 2, 2);
        assertThat(a.mode()).isCloseTo(15, within(1e-9));
        assertThat(a.sd()).isCloseTo(10, within(1e-9));
    }

    @Test
    void oddNutritionStaysInBounds() {
        GammaKernel g = GammaKernelParams.PRIOR.kernelFor(new MealInputs(0, 40, 150, 120, false), 2.0, 2.0);
        assertThat(g.quantile(0.99)).isLessThanOrEqualTo(GammaKernel.MAX_Q99_MIN + 1e-6);
        assertThat(g.k()).isGreaterThanOrEqualTo(GammaKernel.K_MIN);
        GammaKernel nan = GammaKernelParams.PRIOR.kernelFor(new MealInputs(Double.NaN, -3, Double.NaN, 0, false), 1, 1);
        assertThat(nan.mode()).isCloseTo(
                GammaKernelParams.PRIOR.kernelFor(plain(70), 1, 1).mode(), within(1e-9));
    }
}
