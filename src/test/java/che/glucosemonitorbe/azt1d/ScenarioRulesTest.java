package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.AnchorSample;
import che.glucosemonitorbe.hovorka.learning.Regime;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRulesTest {

    private static final int[] HORIZONS = {30, 60, 90, 120, 150, 180};

    /** A forecast result with predicted and actual values at 30, 60, 90, 120, 150 and 180 minutes. */
    private static ForecastResult result(ScenarioKind kind, double start, double[] predicted, double[] actual) {
        List<AnchorSample> samples = new ArrayList<>();
        for (int i = 0; i < HORIZONS.length; i++) {
            samples.add(new AnchorSample(HORIZONS[i], predicted[i], actual[i], start, Regime.CORRECTION, 12));
        }
        ScenarioEpisode ep = new ScenarioEpisode("Subject T", kind, 0L, start, 180);
        return new ForecastResult(ep, ScaleMode.TWIN, TwinScales.neutral(), true, samples);
    }

    private static double[] flat(double v) {
        return new double[]{v, v, v, v, v, v};
    }

    private static double[] line(double... v) {
        return v;
    }

    @Test
    void aCorrectionWhoseForecastBarelyMovesFailsAsFlat() {
        // real fall 3.0 at 120; the forecast falls only 0.5
        ForecastResult r = result(ScenarioKind.CORRECTION_DROP, 10.0,
                line(9.9, 9.8, 9.7, 9.5, 9.5, 9.5), line(9.0, 8.0, 7.4, 7.0, 7.0, 7.0));

        Verdict v = ScenarioRules.evaluate(r);

        assertThat(v.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(v.rule()).isEqualTo("flat-forecast");
    }

    @Test
    void aCorrectionWhoseForecastFollowsRealityPasses() {
        ForecastResult r = result(ScenarioKind.CORRECTION_DROP, 10.0,
                line(9.2, 8.4, 7.6, 7.2, 7.0, 7.0), line(9.0, 8.0, 7.4, 7.0, 7.0, 7.0));

        assertThat(ScenarioRules.evaluate(r).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void aCorrectionWhereRealityDidNotFallIsNotApplicable() {
        ForecastResult r = result(ScenarioKind.CORRECTION_DROP, 10.0,
                line(9.9, 9.8, 9.7, 9.5, 9.5, 9.5), flat(9.6));

        assertThat(ScenarioRules.evaluate(r).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void aMealForecastThatNeverRisesFails() {
        ForecastResult r = result(ScenarioKind.MEAL_WITH_BOLUS, 7.0,
                line(7.2, 7.4, 7.3, 7.2, 7.0, 7.0), line(8.0, 9.5, 10.0, 9.0, 8.0, 7.5));

        Verdict v = ScenarioRules.evaluate(r);

        assertThat(v.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(v.rule()).isEqualTo("no-rise");
    }

    @Test
    void aMealForecastThatRisesPasses() {
        ForecastResult r = result(ScenarioKind.MEAL_NO_BOLUS, 7.0,
                line(7.8, 8.6, 9.0, 8.8, 8.4, 8.0), line(8.0, 9.5, 10.0, 9.0, 8.0, 7.5));

        assertThat(ScenarioRules.evaluate(r).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void aMealWhereRealityDidNotRiseIsNotApplicable() {
        ForecastResult r = result(ScenarioKind.MEAL_NO_BOLUS, 7.0, flat(7.1), line(7.2, 7.8, 8.0, 7.6, 7.2, 7.0));

        assertThat(ScenarioRules.evaluate(r).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void anOvernightForecastThatDriftsFails() {
        ForecastResult r = result(ScenarioKind.OVERNIGHT_STEADY, 6.0,
                line(6.5, 7.5, 8.5, 9.0, 9.0, 9.0), flat(6.5));

        Verdict v = ScenarioRules.evaluate(r);

        assertThat(v.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(v.rule()).isEqualTo("drift");
    }

    @Test
    void anOvernightForecastThatStaysInTheBandPasses() {
        ForecastResult r = result(ScenarioKind.OVERNIGHT_STEADY, 6.0, flat(6.8), flat(6.5));

        assertThat(ScenarioRules.evaluate(r).outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void anOvernightWhereRealityLeftTheBandIsNotApplicable() {
        ForecastResult r = result(ScenarioKind.OVERNIGHT_STEADY, 6.0, flat(6.8),
                line(6.5, 7.0, 8.5, 8.6, 8.0, 7.5));

        assertThat(ScenarioRules.evaluate(r).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void errorIsTheAbsoluteGapAtOneHundredTwentyMinutes() {
        ForecastResult r = result(ScenarioKind.OVERNIGHT_STEADY, 6.0,
                line(6.5, 7.0, 7.5, 8.0, 8.0, 8.0), line(6.5, 6.5, 6.5, 6.5, 6.5, 6.5));

        assertThat(ScenarioRules.evaluate(r).errorMmol()).isEqualTo(1.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void anEpisodeWithNoOneHundredTwentyMinuteSampleIsNotApplicable() {
        ScenarioEpisode ep = new ScenarioEpisode("Subject T", ScenarioKind.CORRECTION_DROP, 0L, 10.0, 180);
        ForecastResult r = new ForecastResult(ep, ScaleMode.TWIN, TwinScales.neutral(), true,
                List.of(new AnchorSample(60, 9.0, 8.0, 10.0, Regime.CORRECTION, 12)));

        Verdict v = ScenarioRules.evaluate(r);

        assertThat(v.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(v.errorMmol()).isNaN();
    }

    @Test
    void anEpisodeWithNoStartGlucoseIsRejectedNotPassed() {
        ForecastResult r = result(ScenarioKind.CORRECTION_DROP, Double.NaN, flat(9.0), flat(9.0));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ScenarioRules.evaluate(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start glucose");
    }
}
