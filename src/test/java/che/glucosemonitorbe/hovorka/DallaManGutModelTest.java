package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * TDD Red->Green unit tests for {@link DallaManGutModel}.
 *
 * Physiological invariants under test:
 * 1.  k_empt at full stomach ≈ K_MAX   (rapid emptying when stomach is full)
 * 2.  k_empt at half-full ≈ K_MIN      (protective pause at 50% fill)
 * 3.  k_empt at empty ≈ midpoint       (rising back toward K_MAX as stomach empties)
 * 4.  k_empt always in [K_MIN, K_MAX]  (physiological bounds)
 * 5.  k_empt with zero meal reference -> K_MIN (degenerate safety case)
 * 6.  Ra = 0 when Qgut = 0
 * 7.  Ra > 0 when Qgut > 0
 * 8.  Ra scales linearly with Qgut
 * 9.  Integration - Ra rises from 0, peaks, then decays toward 0
 * 10. Mass conservation - ∫Ra dt ≈ F × D_mmol over 10 hours
 * 11. Large meal produces higher peak Ra than small meal
 */
class DallaManGutModelTest {

    private DallaManGutModel model;

    @BeforeEach
    void setUp() {
        model = new DallaManGutModel();
    }

    // ---
    // k_empt shape tests
    // ---

    @Test
    void kEmpt_fullStomach_returnsNearKmax() {
        double D = 300.0;  // 300 mmol meal
        double kempt = model.kEmpt(D, D);
        assertThat(kempt).isCloseTo(DallaManGutModel.K_MAX, within(0.002));
    }

    @Test
    void kEmpt_halfFullStomach_returnsNearKmin() {
        double D = 300.0;
        double kempt = model.kEmpt(D / 2.0, D);
        assertThat(kempt).isCloseTo(DallaManGutModel.K_MIN, within(0.002));
    }

    @Test
    void kEmpt_emptyStomach_returnsNearKmax() {
        // At Qsto=0 both tanh terms are near -1, giving result ≈ K_MAX (no food left to protect against).
        double D = 300.0;
        double kempt = model.kEmpt(0.0, D);
        assertThat(kempt).isCloseTo(DallaManGutModel.K_MAX, within(0.003));
    }

    @Test
    void kEmpt_forAllFillLevels_staysInPhysiologicalRange() {
        double D = 300.0;
        for (double qsto = 0.0; qsto <= D; qsto += D / 20.0) {
            double kempt = model.kEmpt(qsto, D);
            assertThat(kempt)
                    .as("k_empt(%.1f, %.1f)", qsto, D)
                    .isBetween(DallaManGutModel.K_MIN, DallaManGutModel.K_MAX);
        }
    }

    @Test
    void kEmpt_zeroMealReference_returnsKmin() {
        assertThat(model.kEmpt(0.0, 0.0)).isCloseTo(DallaManGutModel.K_MIN, within(1e-9));
    }

    // ---
    // Ra (glucose appearance) tests
    // ---

    @Test
    void ra_emptyIntestine_returnsZero() {
        assertThat(model.ra(0.0)).isEqualTo(0.0);
    }

    @Test
    void ra_nonEmptyIntestine_returnsPositive() {
        assertThat(model.ra(50.0)).isGreaterThan(0.0);
    }

    @Test
    void ra_doublingQgut_doublesRa() {
        double ra1 = model.ra(50.0);
        double ra2 = model.ra(100.0);
        assertThat(ra2).isCloseTo(2.0 * ra1, within(1e-9));
    }

    // ---
    // Integration tests
    // ---

    @Test
    void integration_mealAbsorbed_raPeaksAndDecaysToNearZero() {
        double mealMmol = 200.0;

        double qsto1 = mealMmol;
        double qsto2 = 0.0;
        double qgut  = 0.0;
        double peakRa = 0.0;

        for (int t = 0; t < 360; t++) {
            double qsto  = qsto1 + qsto2;
            double kempt = model.kEmpt(qsto, mealMmol);

            qsto1 = Math.max(0.0, qsto1 + (-DallaManGutModel.K_GRI * qsto1));
            qsto2 = Math.max(0.0, qsto2 + (DallaManGutModel.K_GRI * (qsto1 + DallaManGutModel.K_GRI * qsto1) - kempt * qsto2));
            qgut  = Math.max(0.0, qgut  + (kempt * qsto2 - DallaManGutModel.K_ABS * qgut));

            peakRa = Math.max(peakRa, model.ra(qgut));
        }

        double raAtEnd = model.ra(qgut);
        assertThat(peakRa).isGreaterThan(0.01);
        assertThat(raAtEnd).isLessThan(peakRa);
        assertThat(raAtEnd).isLessThan(0.1);
    }

    @Test
    void integration_massConservation_totalAbsorptionMatchesFTimesD() {
        double mealMmol = 200.0;

        double qsto1 = mealMmol;
        double qsto2 = 0.0;
        double qgut  = 0.0;
        double totalRa = 0.0;

        for (int t = 0; t < 600; t++) {
            double qsto  = qsto1 + qsto2;
            double kempt = model.kEmpt(qsto, mealMmol);

            double dQsto1 = -DallaManGutModel.K_GRI * qsto1;
            double dQsto2 = DallaManGutModel.K_GRI * qsto1 - kempt * qsto2;
            double dQgut  = kempt * qsto2 - DallaManGutModel.K_ABS * qgut;

            qsto1 = Math.max(0.0, qsto1 + dQsto1);
            qsto2 = Math.max(0.0, qsto2 + dQsto2);
            qgut  = Math.max(0.0, qgut  + dQgut);
            totalRa += model.ra(qgut);
        }

        double expected = DallaManGutModel.F * mealMmol;
        assertThat(totalRa).isBetween(expected * 0.88, expected * 1.12);
    }

    @Test
    void integration_largeMealHasHigherPeakRaThanSmallMeal() {
        assertThat(peakRaForMeal(400.0)).isGreaterThan(peakRaForMeal(100.0));
    }

    // -- Helper ----------------------------------------------------------------

    private double peakRaForMeal(double mealMmol) {
        double qsto1 = mealMmol, qsto2 = 0.0, qgut = 0.0, peak = 0.0;
        for (int t = 0; t < 600; t++) {
            double qsto  = qsto1 + qsto2;
            double kempt = model.kEmpt(qsto, mealMmol);
            double dQsto1 = -DallaManGutModel.K_GRI * qsto1;
            double dQsto2 = DallaManGutModel.K_GRI * qsto1 - kempt * qsto2;
            double dQgut  = kempt * qsto2 - DallaManGutModel.K_ABS * qgut;
            qsto1 = Math.max(0.0, qsto1 + dQsto1);
            qsto2 = Math.max(0.0, qsto2 + dQsto2);
            qgut  = Math.max(0.0, qgut  + dQgut);
            peak  = Math.max(peak, model.ra(qgut));
        }
        return peak;
    }
}
