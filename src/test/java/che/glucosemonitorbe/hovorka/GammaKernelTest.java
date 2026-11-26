package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GammaKernelTest {

    @Test
    void modeAndSdAreExact() {
        GammaKernel g = GammaKernel.fromModeAndSd(48, 53);
        assertThat(g.mode()).isCloseTo(48, within(1e-9));
        assertThat(g.sd()).isCloseTo(53, within(1e-9));
    }

    @Test
    void rescueKernelIsK4Theta5() {
        GammaKernel g = GammaKernel.fromModeAndSd(15, 10);
        assertThat(g.k()).isCloseTo(4, within(1e-9));
        assertThat(g.theta()).isCloseTo(5, within(1e-9));
    }

    @Test
    void massIsConserved() {
        GammaKernel g = GammaKernel.fromModeAndSd(48, 53);
        double sum = 0;
        for (int m = 1; m <= 2000; m++) sum += g.cdf(m) - g.cdf(m - 1);
        assertThat(sum).isCloseTo(1.0, within(1e-6));
        assertThat(g.cdf(0)).isZero();
        assertThat(g.cdf(-5)).isZero();
        assertThat(g.pdf(-5)).isZero();
    }

    @Test
    void extremeInputsStayInBounds() {
        double[][] inputs = {{1, 1}, {500, 500}, {180, 150}, {Double.NaN, 40}};
        for (double[] in : inputs) {
            GammaKernel g = GammaKernel.fromModeAndSd(in[0], in[1]);
            assertThat(g.mode()).isBetween(GammaKernel.MODE_MIN - 1e-9, GammaKernel.MODE_MAX + 1e-9);
            assertThat(g.sd()).isBetween(GammaKernel.SD_MIN - 1e-9, GammaKernel.SD_MAX + 1e-9);
            assertThat(g.k()).isGreaterThanOrEqualTo(GammaKernel.K_MIN);
            assertThat(g.quantile(0.99)).isLessThanOrEqualTo(GammaKernel.MAX_Q99_MIN + 1e-6);
        }
    }
}
