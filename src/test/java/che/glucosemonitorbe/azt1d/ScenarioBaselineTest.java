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
        ScenarioBaseline current = with(1.0, 0); // everything the baseline recorded is still produced
        current.put(ScenarioBaseline.key(ScenarioKind.MEAL_NO_BOLUS, "Subject 9"), 9.0, 9);

        assertThat(with(1.0, 0).regressions(current)).isEmpty();
    }

    @Test
    void aCellTheCurrentRunNoLongerProducesIsARegression() {
        ScenarioBaseline recorded = with(1.0, 0);

        assertThat(recorded.regressions(new ScenarioBaseline()))
                .singleElement().asString().contains("CORRECTION_DROP|Subject 1").contains("no longer");
    }

    @Test
    void aCellWithNoErrorSamplesRoundTripsAndIsNotComparedOnError() throws Exception {
        Path file = dir.resolve("nan.json");
        String key = ScenarioBaseline.key(ScenarioKind.MEAL_WITH_BOLUS, "Subject 4");
        ScenarioBaseline recorded = new ScenarioBaseline();
        recorded.put(key, Double.NaN, 0);

        recorded.save(file);
        ScenarioBaseline loaded = ScenarioBaseline.load(file).orElseThrow();

        assertThat(loaded.get(key).orElseThrow().medianErrorMmol()).isNaN();
        ScenarioBaseline current = new ScenarioBaseline();
        current.put(key, 5.0, 0);
        assertThat(loaded.regressions(current)).isEmpty();
    }

    @Test
    void aPassingEpisodeThatNoLongerPassesIsARegression() {
        String episode = ScenarioBaseline.episodeKey(ScenarioKind.CORRECTION_DROP, "Subject 1", 123L);
        ScenarioBaseline recorded = with(1.0, 1);
        recorded.addPassing(episode);
        ScenarioBaseline current = with(1.0, 1); // same counts: one episode swapped from pass to fail

        assertThat(recorded.regressions(current)).singleElement().asString().contains(episode);
    }

    @Test
    void anEpisodeThatNeverPassedMayStayFailingAndANewPassIsNotARegression() {
        ScenarioBaseline recorded = with(1.0, 1);
        ScenarioBaseline current = with(1.0, 1);
        current.addPassing(ScenarioBaseline.episodeKey(ScenarioKind.CORRECTION_DROP, "Subject 1", 456L));

        assertThat(recorded.regressions(current)).isEmpty();
    }

    @Test
    void passingEpisodesRoundTrip() throws Exception {
        Path file = dir.resolve("passing.json");
        String episode = ScenarioBaseline.episodeKey(ScenarioKind.OVERNIGHT_STEADY, "Subject 2", 789L);
        ScenarioBaseline b = with(1.0, 0);
        b.addPassing(episode);

        b.save(file);

        assertThat(ScenarioBaseline.load(file).orElseThrow().passingEpisodes()).containsExactly(episode);
    }
}
