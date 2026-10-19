package che.glucosemonitorbe.empirical;

import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Golden test: the Java engine must reproduce the reference forecasts of
 * {@code specs/twin-port/twin-reference.js} (the oracle) within 0.5 mg/dL at every 30-min point,
 * for every anchor in {@code golden-forecasts.json}.
 */
class EmpiricalTwinGoldenTest {

    private static final double TOLERANCE_MGDL = 0.5;

    @Test
    void reproducesTheReferenceForecastAtEveryGoldenAnchor() {
        TwinFixtures.assumePresent();
        List<PredictionReplayEngine.Reading> readings = TwinFixtures.readings();
        List<EmpiricalEvent> events = TwinFixtures.events();
        List<TwinFixtures.Golden> golden = TwinFixtures.golden();
        EmpiricalTwinParams params = EmpiricalTwinParams.defaults();

        assertThat(golden).hasSize(86);
        double worst = 0;
        for (TwinFixtures.Golden g : golden) {
            List<EmpiricalTwinEngine.Point> forecast =
                    EmpiricalTwinEngine.forecast(g.anchorEpochMs(), readings, events, params);

            assertThat(forecast).as("anchor %d: 5-min points from 0 to +240", g.anchorEpochMs()).hasSize(49);
            for (int i = 0; i < g.minutes().length; i++) {
                EmpiricalTwinEngine.Point p = forecast.get(g.minutes()[i] / 5);
                assertThat(p.minute()).isEqualTo(g.minutes()[i]);
                double mgdl = p.mmol() * EmpiricalTwinEngine.MGDL_PER_MMOL;
                assertThat(mgdl)
                        .as("anchor %d at +%d min", g.anchorEpochMs(), g.minutes()[i])
                        .isCloseTo(g.mgdl()[i], within(TOLERANCE_MGDL));
                worst = Math.max(worst, Math.abs(mgdl - g.mgdl()[i]));
            }
        }
        System.out.printf("empirical twin golden check: max |diff| = %.2f mg/dL over %d anchors%n",
                worst, golden.size());
    }

    @Test
    void goldenAnchorsAreExactlyTheDistinctInsulinMealAndFastSugarMinutes() {
        TwinFixtures.assumePresent();
        List<Long> anchors = EmpiricalTwinEngine.anchors(TwinFixtures.events());

        assertThat(anchors).containsExactlyElementsOf(
                TwinFixtures.golden().stream().map(TwinFixtures.Golden::anchorEpochMs).toList());
    }

    @Test
    void defaultParamsMatchParamsJson() throws Exception {
        TwinFixtures.assumePresent();
        JsonNode json = TwinFixtures.read("params.json");
        EmpiricalTwinParams defaults = EmpiricalTwinParams.defaults();

        RecordComponent[] components = EmpiricalTwinParams.class.getRecordComponents();
        assertThat(components).hasSize(json.size());
        for (RecordComponent c : components) {
            assertThat(json.has(c.getName())).as("params.json has %s", c.getName()).isTrue();
            assertThat((double) c.getAccessor().invoke(defaults))
                    .as(c.getName())
                    .isEqualTo(json.get(c.getName()).get("value").asDouble());
        }
    }
}
