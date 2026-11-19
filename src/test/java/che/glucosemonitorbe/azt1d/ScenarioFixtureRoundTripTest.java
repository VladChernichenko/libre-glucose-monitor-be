package che.glucosemonitorbe.azt1d;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    private static ScenarioFixture shiftedReadings(ScenarioFixture f, int minutes) {
        List<ScenarioFixture.Reading> shifted = f.readings().stream()
                .map(r -> new ScenarioFixture.Reading(r.minute() + minutes, r.mmol())).toList();
        return new ScenarioFixture(f.id(), f.kind(), f.horizonMin(), f.hourOfDay(), f.weekday(), f.profile(),
                f.isfScale(), f.agScale(), f.egpScale(), f.twinApplied(), shifted, f.events(), f.expected());
    }

    @Test
    void theStartGlucoseIsTheReadingNearestTheStartWhenNoneSitsExactlyOnIt() {
        ScenarioFixture f = shiftedReadings(exported(), 2); // no reading at minute 0; one at minute 2

        double start = f.replay().episode().startMmol();

        double nearest = f.readings().stream().filter(r -> r.minute() == 2).findFirst().orElseThrow().mmol();
        assertThat(start).isEqualTo(nearest);
    }

    @Test
    void aFixtureWithNoReadingNearTheStartCannotBeReplayed() {
        ScenarioFixture e = exported();
        List<ScenarioFixture.Reading> farFromStart = e.readings().stream()
                .filter(r -> Math.abs(r.minute()) > 7).toList();
        ScenarioFixture f = new ScenarioFixture(e.id(), e.kind(), e.horizonMin(), e.hourOfDay(), e.weekday(),
                e.profile(), e.isfScale(), e.agScale(), e.egpScale(), e.twinApplied(), farFromStart, e.events(),
                e.expected());

        org.assertj.core.api.Assertions.assertThatThrownBy(f::replay)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(f.id());
    }

    @Test
    void exportRoundsGlucoseToATenthAndTheProfileToTwoSignificantFigures() {
        ScenarioFixture f = exported();

        assertThat(f.readings()).allSatisfy(r ->
                assertThat(r.mmol() * 10).isEqualTo(Math.rint(r.mmol() * 10), org.assertj.core.data.Offset.offset(1e-9)));
        for (double v : new double[]{f.profile().weightKg(), f.profile().isfMmolPerU(), f.profile().carbRatioGPerU()}) {
            assertThat(new java.math.BigDecimal(v).round(new java.math.MathContext(2)).doubleValue()).isEqualTo(v);
        }
    }
}
