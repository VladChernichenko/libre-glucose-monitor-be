package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MacroNutrientGastricModelTest {

    // -- fiberViscosityFactor -------------------------------------------------

    @Test
    void fiberViscosity_isOneWithNoFiber_andGrowsExponentially() {
        assertThat(MacroNutrientGastricModel.fiberViscosityFactor(0)).isEqualTo(1.0);
        assertThat(MacroNutrientGastricModel.fiberViscosityFactor(10)).isCloseTo(Math.exp(0.2), within(1e-12));
        assertThat(MacroNutrientGastricModel.fiberViscosityFactor(-3)).isEqualTo(1.0);
    }

    // -- bolusStrategy --------------------------------------------------------

    @Test
    void bolusStrategy_lowFatLowProtein_normal() {
        assertThat(MacroNutrientGastricModel.bolusStrategy(10, 10)).isEqualTo("NORMAL");
    }

    @Test
    void bolusStrategy_highFatHighProtein_squareWave() {
        // 25g fat, 20g protein -> both thresholds exceeded
        assertThat(MacroNutrientGastricModel.bolusStrategy(25, 20)).isEqualTo("SQUARE_WAVE");
    }

    @Test
    void bolusStrategy_highProteinOnly_squareWave() {
        // 45g protein, 5g fat -> protein > 40g threshold
        assertThat(MacroNutrientGastricModel.bolusStrategy(5, 45)).isEqualTo("SQUARE_WAVE");
    }

    @Test
    void bolusStrategy_highFatLowProtein_normal() {
        // 30g fat but only 10g protein -> SQUARE_WAVE needs both thresholds or protein alone
        assertThat(MacroNutrientGastricModel.bolusStrategy(30, 10)).isEqualTo("NORMAL");
    }
}
