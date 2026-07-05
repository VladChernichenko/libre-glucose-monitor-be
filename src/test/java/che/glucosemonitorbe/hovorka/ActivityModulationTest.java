package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the activity-modulation math (clamp, tail decay, gains). No ODE/Spring needed. */
class ActivityModulationTest {

    @Test
    @DisplayName("clampIntensity keeps a(t) within [0,1] and maps NaN to 0")
    void clampsIntensity() {
        assertThat(ActivityModulation.clampIntensity(Double.NaN)).isEqualTo(0.0);
        assertThat(ActivityModulation.clampIntensity(1.5)).isEqualTo(1.0);
        assertThat(ActivityModulation.clampIntensity(-0.5)).isEqualTo(0.0);
        assertThat(ActivityModulation.clampIntensity(0.4)).isEqualTo(0.4);
    }

    @Test
    @DisplayName("the sensitivity reservoir tracks activity, then decays with the configured half-life")
    void tailDecaysWithHalfLife() {
        ActivityModulation m = new ActivityModulation(1.0, 0.02, 120.0);
        assertThat(m.stepSensitivity(1.0)).isEqualTo(1.0);          // charges to current intensity

        double afterOneHalfLife = 0.0;
        for (int i = 0; i < 120; i++) afterOneHalfLife = m.stepSensitivity(0.0);
        assertThat(afterOneHalfLife).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));

        double afterTwoHalfLives = afterOneHalfLife;
        for (int i = 0; i < 120; i++) afterTwoHalfLives = m.stepSensitivity(0.0);
        assertThat(afterTwoHalfLives).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("gains: sensitivity factor and insulin-independent uptake scale with intensity")
    void gainsScaleWithIntensity() {
        ActivityModulation m = new ActivityModulation();
        assertThat(m.insulinSensitivityFactor(0.0)).isEqualTo(1.0);
        assertThat(m.insulinSensitivityFactor(1.0)).isEqualTo(1.0 + ActivityModulation.GAIN_INSULIN);
        assertThat(m.uptakeRate(0.0)).isEqualTo(0.0);
        assertThat(m.uptakeRate(1.0)).isEqualTo(ActivityModulation.GAIN_INDEP);
    }
}
