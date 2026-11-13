package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.TwinScales;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Always-run check that each committed fixture still behaves as recorded. Zero fixtures is allowed. */
class ScenarioFixturesTest {

    /** What got worse when the fixture is replayed today compared with what was recorded. */
    static List<String> regressions(ScenarioFixture f) {
        Verdict now = ScenarioRules.evaluate(f.replay());
        ScenarioFixture.Expectation was = f.expected();
        List<String> out = new ArrayList<>();
        if (was.outcome() == Outcome.PASS && now.outcome() != Outcome.PASS) {
            out.add(f.id() + ": recorded PASS, now " + now.outcome() + " (" + now.detail() + ")");
        }
        if (was.outcome() == Outcome.NOT_APPLICABLE && now.outcome() != Outcome.NOT_APPLICABLE) {
            out.add(f.id() + ": recorded NOT_APPLICABLE, now " + now.outcome());
        }
        double limit = was.errorMmol() * ScenarioBaseline.REGRESSION_FACTOR + ScenarioBaseline.ABSOLUTE_MARGIN;
        if (!Double.isNaN(now.errorMmol()) && now.errorMmol() > limit) {
            out.add(String.format("%s: error %.2f above the allowed %.2f", f.id(), now.errorMmol(), limit));
        }
        return out;
    }

    @Test
    void committedFixturesDoNotRegress() throws Exception {
        List<Path> files = ScenarioFixtureGuardTest.fixtureFiles();
        if (files.isEmpty()) {
            System.out.println("no committed fixtures yet in " + ScenarioFixtureGuardTest.fixturesDir());
            return;
        }
        List<String> regressions = new ArrayList<>();
        for (Path file : files) regressions.addAll(regressions(ScenarioFixtureExporter.read(file)));
        assertThat(regressions).isEmpty();
    }

    @Test
    void aFixtureWhoseRecordedPassNowFailsIsARegression() {
        long dose = java.time.LocalDateTime.of(2024, 1, 1, 14, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        ScenarioEpisode ep = new ScenarioEpisode("Subject 7", ScenarioKind.CORRECTION_DROP, dose, 11.0, 180);
        Azt1dDataset.Subject s = PinnedTwinScenarioTest.subjectWhoseGlucoseFollowsTheModel(ep);
        ScenarioFixture passing = ScenarioFixtureExporter.export(s, ep,
                new ScenarioForecaster(s).forecast(ep, ScaleMode.NEUTRAL), 1);
        assertThat(passing.expected().outcome()).isEqualTo(Outcome.PASS);
        assertThat(regressions(passing)).isEmpty();

        ScenarioFixture pinned = passing.withScales(TwinScales.MIN_SCALE, 1.0, 1.0);

        assertThat(regressions(pinned)).anyMatch(r -> r.contains("recorded PASS, now FAIL"));
    }
}
