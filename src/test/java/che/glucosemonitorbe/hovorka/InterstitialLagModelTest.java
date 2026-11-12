package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InterstitialLagModelTest {

    private double savedTau;

    @BeforeEach
    void usePhysiologicalTau() {
        savedTau = InterstitialLagModel.tauMinutes;
        InterstitialLagModel.tauMinutes = InterstitialLagModel.PHYSIOLOGICAL_TAU_MIN;
    }

    @AfterEach
    void restoreTau() {
        InterstitialLagModel.tauMinutes = savedTau;
    }

    @Test
    void plasmaFromSensor_sevenMinuteLag_clampedTrend() {
        assertThat(InterstitialLagModel.PHYSIOLOGICAL_TAU_MIN).isEqualTo(7.0);
        assertThat(InterstitialLagModel.plasmaFromSensor(6.0, 0.1)).isCloseTo(6.7, within(1e-12));
        assertThat(InterstitialLagModel.plasmaFromSensor(6.0, 0.0)).isEqualTo(6.0);
        assertThat(InterstitialLagModel.plasmaFromSensor(6.0, -0.1)).isCloseTo(5.3, within(1e-12));
        assertThat(InterstitialLagModel.plasmaFromSensor(6.0, 1.0)).isCloseTo(6.0 + 7.0 * 0.15, within(1e-12));
        assertThat(InterstitialLagModel.plasmaFromSensor(6.0, Double.NaN)).isEqualTo(6.0);
    }
}
