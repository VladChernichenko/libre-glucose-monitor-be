package che.glucosemonitorbe.azt1d;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioFixtureRoundTripTest {

    private static final long DOSE_AT = java.time.LocalDateTime.of(2024, 1, 1, 14, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();

    @TempDir Path dir;

    private static ScenarioEpisode episode() {
        return new ScenarioEpisode("Subject 7", ScenarioKind.CORRECTION_DROP, DOSE_AT, 11.0, 180);
    }

    private static ScenarioFixture exported() {
        ScenarioEpisode ep = episode();
        Azt1dDataset.Subject s = PinnedTwinScenarioTest.subjectWhoseGlucoseFollowsTheModel(ep);
        ForecastResult twin = new ScenarioForecaster(s).forecast(ep, ScaleMode.TWIN);
        return ScenarioFixtureExporter.export(s, ep, twin, 3);
    }

    @Test
    void anExportedEpisodeReplaysToItsRecordedOutcome() throws Exception {
        ScenarioFixture exported = exported();

        ScenarioFixtureExporter.write(exported, dir);
        ScenarioFixture read = ScenarioFixtureExporter.read(dir.resolve(exported.id() + ".json"));
        Verdict replayed = ScenarioRules.evaluate(read.replay());

        assertThat(read).isEqualTo(exported);
        assertThat(replayed.outcome()).isEqualTo(read.expected().outcome());
        assertThat(replayed.errorMmol()).isEqualTo(read.expected().errorMmol(), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void theFixtureIsNamedByKindAndIndexAndKeepsOnlyClockHourAndWeekday() {
        ScenarioFixture f = exported();

        assertThat(f.id()).isEqualTo("correction-drop-03");
        assertThat(f.kind()).isEqualTo(ScenarioKind.CORRECTION_DROP);
        assertThat(f.hourOfDay()).isEqualTo(14);
        assertThat(f.weekday()).isEqualTo(1); // 2024-01-01 was a Monday
        assertThat(f.readings().get(0).minute()).isEqualTo(-480);
        assertThat(f.readings()).anyMatch(r -> r.minute() == 0 && r.mmol() == 11.0);
        assertThat(f.events()).anyMatch(e -> e.minute() == 0 && e.insulinU() == 2.0);
    }

    @Test
    void anExportedFixtureCarriesNoDateAndNoSubjectId() throws Exception {
        ScenarioFixtureExporter.write(exported(), dir);

        String json = Files.readString(dir.resolve("correction-drop-03.json"));

        assertThat(ScenarioFixture.privacyViolations(json)).isEmpty();
        assertThat(json).doesNotContain("Subject 7");
    }
}
