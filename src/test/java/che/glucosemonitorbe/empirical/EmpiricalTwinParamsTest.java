package che.glucosemonitorbe.empirical;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EmpiricalTwinParamsTest {

    @Test
    void dosingParametersComeFromTheUser() {
        EmpiricalTwinParams p = EmpiricalTwinParams.forUser(3.0, 15.0, 5.0, 75.0);

        assertThat(p.isf()).isCloseTo(3.0 * EmpiricalTwinEngine.MGDL_PER_MMOL, within(1e-9));
        assertThat(p.crAM()).isEqualTo(15.0);
        assertThat(p.crDay()).isEqualTo(15.0);
        assertThat(p.csf()).isCloseTo(p.isf() / 15.0, within(1e-9));
        assertThat(p.dia()).isEqualTo(300.0);
        assertThat(p.peak()).isEqualTo(75.0);
    }

    @Test
    void shapeParametersStayAtTheModelDefaults() {
        EmpiricalTwinParams d = EmpiricalTwinParams.defaults();
        EmpiricalTwinParams p = EmpiricalTwinParams.forUser(3.0, 15.0, 5.0, 75.0);

        assertThat(p.kMeal()).isEqualTo(d.kMeal());
        assertThat(p.kFast()).isEqualTo(d.kFast());
        assertThat(p.giPow()).isEqualTo(d.giPow());
        assertThat(p.walkDrop()).isEqualTo(d.walkDrop());
        assertThat(p.alcDrop()).isEqualTo(d.alcDrop());
    }

    @Test
    void aMissingOrImplausibleSettingKeepsItsDefault() {
        EmpiricalTwinParams d = EmpiricalTwinParams.defaults();

        assertThat(EmpiricalTwinParams.forUser(null, null, 4.0, 55.0)).isEqualTo(d);
        // 2 g/U is outside the 3-30 envelope dosing accepts, so it is not used here either.
        EmpiricalTwinParams badRatio = EmpiricalTwinParams.forUser(null, 2.0, 4.0, 55.0);
        assertThat(badRatio.crDay()).isEqualTo(d.crDay());
        assertThat(EmpiricalTwinParams.forUser(-1.0, null, 0.0, Double.NaN)).isEqualTo(d);
    }

    @Test
    void aUsersDoseFallsByTheirOwnSensitivity() {
        // 4 U with nothing else on board, read at the end of the insulin's action.
        long t0 = 1_700_000_000_000L;
        var cgm = java.util.List.of(
                new che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine.Reading(t0, 15.0));
        var dose = java.util.List.of(new EmpiricalEvent(
                EmpiricalEvent.Type.INSULIN, t0, null, 4.0, 12.0, null, null, null));

        double fall = 15.0 - EmpiricalTwinEngine.forecast(t0, cgm, dose,
                EmpiricalTwinParams.forUser(3.0, 15.0, 4.0, 55.0)).get(48).mmol();

        assertThat(fall).isCloseTo(12.0, within(0.05));
    }
}
