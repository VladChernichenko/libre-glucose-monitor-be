package che.glucosemonitorbe.azt1d;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioBaselineTest {

    @TempDir Path dir;

    private static ScenarioBaseline with(double medianError, int failCount) {
        ScenarioBaseline b = new ScenarioBaseline();
        b.put(ScenarioBaseline.key(ScenarioKind.CORRECTION_DROP, "Subject 1"), medianError, failCount);
        return b;
    }

    @Test
    void roundTripsEntries() throws Exception {
        Path file = dir.resolve("nested/baseline.json");
        ScenarioBaseline b = with(1.25, 2);
        b.put(ScenarioBaseline.key(ScenarioKind.OVERNIGHT_STEADY, "Subject 2"), 0.5, 0);

        b.save(file);
        ScenarioBaseline loaded = ScenarioBaseline.load(file).orElseThrow();

        assertThat(loaded.get(ScenarioBaseline.key(ScenarioKind.CORRECTION_DROP, "Subject 1")))
                .contains(new ScenarioBaseline.Entry(1.25, 2));
        assertThat(loaded.get(ScenarioBaseline.key(ScenarioKind.OVERNIGHT_STEADY, "Subject 2")))
                .contains(new ScenarioBaseline.Entry(0.5, 0));
    }

    @Test
    void missingFileIsEmpty() {
        assertThat(ScenarioBaseline.load(dir.resolve("absent.json"))).isEmpty();
    }

    @Test
    void corruptFileThrowsWithThePath() throws Exception {
        Path file = dir.resolve("corrupt.json");
        Files.writeString(file, "{ not json");

        assertThatThrownBy(() -> ScenarioBaseline.load(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void aHigherFailCountIsARegression() {
        assertThat(with(1.0, 2).regressions(with(1.0, 3))).hasSize(1);
        assertThat(with(1.0, 2).regressions(with(1.0, 2))).isEmpty();
        assertThat(with(1.0, 2).regressions(with(1.0, 1))).isEmpty();
    }

    @Test
    void aMedianErrorWithinTheMarginIsNotARegression() {
        ScenarioBaseline recorded = with(1.0, 0);

        assertThat(recorded.regressions(with(1.34, 0))).isEmpty();
        assertThat(recorded.regressions(with(1.36, 0))).hasSize(1);
    }

    @Test
    void anEntryTheBaselineNeverRecordedIsNotARegression() {
        ScenarioBaseline current = new ScenarioBaseline();
        current.put(ScenarioBaseline.key(ScenarioKind.MEAL_NO_BOLUS, "Subject 9"), 9.0, 9);

        assertThat(with(1.0, 0).regressions(current)).isEmpty();
    }
}
