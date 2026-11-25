package che.glucosemonitorbe.empirical;

import che.glucosemonitorbe.domain.MealWindow;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EmpiricalTwinParamsTest {

    @Test
    void dosingParametersComeFromTheUser() {
        EmpiricalTwinParams p = EmpiricalTwinParams.forUser(3.0, 15.0, 5.0, 75.0);

        assertThat(p.isf()).isCloseTo(3.0 * EmpiricalTwinEngine.MGDL_PER_MMOL, within(1e-9));
        assertThat(p.crAt(8)).isEqualTo(15.0);
        assertThat(p.crAt(13)).isEqualTo(15.0);
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
        assertThat(badRatio.crAt(13)).isEqualTo(d.crAt(13));
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

    @Test
    void eachWindowUsesItsOwnRatio() {
        var p = EmpiricalTwinParams.forUser(3.0, 10.0,
                Map.of(MealWindow.BREAKFAST, 6.0, MealWindow.DINNER, 12.0), 4.0, 55.0);
        assertThat(p.crAt(8.5)).isEqualTo(6.0);
        assertThat(p.crAt(12.0)).isEqualTo(10.0);
        assertThat(p.crAt(18.0)).isEqualTo(12.0);
        assertThat(p.crAt(23.5)).isEqualTo(10.0);
    }

    @Test
    void withoutAnyRatioTheWindowDefaultsStay() {
        var p = EmpiricalTwinParams.forUser(null, null, Map.of(), 4.0, 55.0);
        assertThat(p.crAt(8.0)).isEqualTo(7.0);
        assertThat(p.crAt(13.0)).isEqualTo(10.0);
    }

    @Test
    void anImplausibleWindowRatioFallsBackToTheSingleRatio() {
        assertThat(EmpiricalTwinParams.forUser(3.0, 10.0, Map.of(MealWindow.LUNCH, 2.0), 4.0, 55.0).crAt(12.0))
                .isEqualTo(10.0);
    }

    @Test
    void aMealIsCountedAtItsWindowsRatio() {
        long t0 = 1_700_000_000_000L;
        var cgm = java.util.List.of(
                new che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine.Reading(t0, 10.0));
        var meal = java.util.List.of(new EmpiricalEvent(
                EmpiricalEvent.Type.MEAL, t0, null, 60.0, 8.0, null, null, null));

        double riseAt6 = EmpiricalTwinEngine.forecast(t0, cgm, meal,
                EmpiricalTwinParams.forUser(3.0, 10.0, Map.of(MealWindow.BREAKFAST, 6.0), 4.0, 55.0)).get(48).mmol() - 10.0;
        double riseAt12 = EmpiricalTwinEngine.forecast(t0, cgm, meal,
                EmpiricalTwinParams.forUser(3.0, 10.0, Map.of(MealWindow.BREAKFAST, 12.0), 4.0, 55.0)).get(48).mmol() - 10.0;

        assertThat(riseAt6 / riseAt12).isCloseTo(2.0, within(0.01));
    }
}
