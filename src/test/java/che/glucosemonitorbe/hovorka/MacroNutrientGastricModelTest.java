package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MacroNutrientGastricModelTest {

    private static final double TMAX_FACTOR = HovorkaParameterService.HALF_LIFE_TO_TMAX_G;

    // ── computeTMaxG ─────────────────────────────────────────────────────────

    @Test
    void noMacros_returnsFallbackTMaxG() {
        double tMaxG = MacroNutrientGastricModel.computeTMaxG(0, 0, 0, 0, TMAX_FACTOR);
        // fallback t½ = 45 min → tMaxG = 45 / 1.68 ≈ 26.8 min
        assertThat(tMaxG).isCloseTo(45.0 / TMAX_FACTOR, within(0.1));
    }

    @Test
    void pureCarbMeal_tMaxGCloseToUserHalfLife() {
        // 60 g carbs, no fat/protein → β = 1.05 (carb baseline)
        // kcal = 240, d = 240/250 = 0.96 kcal/mL
        // t½_base = 9 + 27.5 * 0.96 = 35.4 min; β-normalised ≈ 35.4 (no change)
        double tMaxG = MacroNutrientGastricModel.computeTMaxG(60, 0, 0, 0, TMAX_FACTOR);
        double tHalfExpected = 9.0 + 27.5 * (240.0 / 250.0);
        assertThat(tMaxG).isCloseTo(tHalfExpected / TMAX_FACTOR, within(0.5));
    }

    @Test
    void highFatMeal_longerTMaxGThanPureCarbMeal() {
        // Buffalo wings: 40g carbs, 30g protein, 25g fat
        double tMaxGHFHP = MacroNutrientGastricModel.computeTMaxG(40, 30, 25, 5, TMAX_FACTOR);
        double tMaxGCarb = MacroNutrientGastricModel.computeTMaxG(40, 0, 0, 0, TMAX_FACTOR);
        assertThat(tMaxGHFHP).isGreaterThan(tMaxGCarb);
    }

    @Test
    void fiberIncreasestMaxG() {
        double withoutFiber = MacroNutrientGastricModel.computeTMaxG(50, 10, 10, 0, TMAX_FACTOR);
        double withFiber    = MacroNutrientGastricModel.computeTMaxG(50, 10, 10, 10, TMAX_FACTOR);
        assertThat(withFiber).isGreaterThan(withoutFiber);
    }

    @Test
    void tMaxGClampedAboveMinimum() {
        // Very small meal → t½ calculation should stay ≥ 15 min
        double tMaxG = MacroNutrientGastricModel.computeTMaxG(5, 0, 0, 0, TMAX_FACTOR);
        assertThat(tMaxG).isGreaterThanOrEqualTo(15.0 / TMAX_FACTOR);
    }

    // ── weightedBeta ─────────────────────────────────────────────────────────

    @Test
    void weightedBeta_pureFat_returnsBetaFat() {
        double beta = MacroNutrientGastricModel.weightedBeta(0, 0, 100);
        assertThat(beta).isCloseTo(MacroNutrientGastricModel.BETA_FAT, within(0.01));
    }

    @Test
    void weightedBeta_pureCarb_returnsBetaCarbs() {
        double beta = MacroNutrientGastricModel.weightedBeta(100, 0, 0);
        assertThat(beta).isCloseTo(MacroNutrientGastricModel.BETA_CARBS, within(0.01));
    }

    @Test
    void weightedBeta_noMacros_returnsBetaCarbs() {
        double beta = MacroNutrientGastricModel.weightedBeta(0, 0, 0);
        assertThat(beta).isEqualTo(MacroNutrientGastricModel.BETA_CARBS);
    }

    @Test
    void weightedBeta_mixed_betweenMinAndMax() {
        double beta = MacroNutrientGastricModel.weightedBeta(30, 20, 15);
        assertThat(beta).isBetween(MacroNutrientGastricModel.BETA_CARBS, MacroNutrientGastricModel.BETA_FAT);
    }

    // ── bolusStrategy ────────────────────────────────────────────────────────

    @Test
    void bolusStrategy_lowFatLowProtein_normal() {
        assertThat(MacroNutrientGastricModel.bolusStrategy(10, 10)).isEqualTo("NORMAL");
    }

    @Test
    void bolusStrategy_highFatHighProtein_squareWave() {
        // 25g fat, 20g protein → both thresholds exceeded
        assertThat(MacroNutrientGastricModel.bolusStrategy(25, 20)).isEqualTo("SQUARE_WAVE");
    }

    @Test
    void bolusStrategy_highProteinOnly_squareWave() {
        // 45g protein, 5g fat → protein > 40g threshold
        assertThat(MacroNutrientGastricModel.bolusStrategy(5, 45)).isEqualTo("SQUARE_WAVE");
    }

    @Test
    void bolusStrategy_highFatLowProtein_normal() {
        // 30g fat but only 10g protein → SQUARE_WAVE needs both thresholds or protein alone
        assertThat(MacroNutrientGastricModel.bolusStrategy(30, 10)).isEqualTo("NORMAL");
    }
}
