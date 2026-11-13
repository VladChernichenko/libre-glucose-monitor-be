package che.glucosemonitorbe.azt1d;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data-gated: writes a curated set of de-identified fixtures (up to 6 per kind, spread across
 * subjects, applicable episodes first) into {@code build/scenario-fixtures}. Nothing is written into
 * the committed fixture folder: moving files there waits for the owner to confirm the dataset licence.
 */
class Azt1dScenarioFixtureExportTest {

    private static final int PER_KIND = 6;
    private static final Path OUT = Path.of("build", "scenario-fixtures");

    private record Candidate(Azt1dDataset.Subject subject, ScenarioForecaster forecaster, ScenarioEpisode episode,
                             ForecastResult twin, boolean applicable) {}

    @Test
    void exportsACuratedSet() throws Exception {
        Path root = Path.of(System.getProperty("azt1d.dir", ""));
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root.resolve("CGM Records")),
                "AZT1D dataset not present - set -Dazt1d.dir=\"/path/to/AZT1D 2025\" to run");

        List<Azt1dDataset.Subject> subjects = Azt1dDataset.loadAll(root);
        Map<String, List<Azt1dDataset.BolusRecord>> logs = Azt1dDataset.loadAllBolusLogs(root);
        if (Files.isDirectory(OUT)) {
            try (var old = Files.list(OUT)) { for (Path p : old.toList()) Files.delete(p); }
        }

        int written = 0;
        long bytes = 0;
        for (ScenarioKind kind : ScenarioKind.values()) {
            List<List<Candidate>> bySubject = new ArrayList<>();
            for (Azt1dDataset.Subject s : subjects) {
                ScenarioForecaster forecaster = new ScenarioForecaster(s);
                List<Candidate> mine = new ArrayList<>();
                for (ScenarioEpisode ep : ScenarioMiner.mine(s, logs.getOrDefault(s.id(), List.of()), kind)) {
                    ForecastResult twin = forecaster.forecast(ep, ScaleMode.TWIN);
                    mine.add(new Candidate(s, forecaster, ep, twin,
                            ScenarioRules.evaluate(twin).outcome() != Outcome.NOT_APPLICABLE));
                }
                mine.sort((a, b) -> Boolean.compare(b.applicable(), a.applicable()));
                if (!mine.isEmpty()) bySubject.add(mine);
            }
            // Round-robin across subjects so no one subject fills the set.
            int index = 0;
            for (int round = 0; index < PER_KIND; round++) {
                boolean any = false;
                for (List<Candidate> list : bySubject) {
                    if (round < list.size() && index < PER_KIND) {
                        Candidate c = list.get(round);
                        ScenarioFixture f = ScenarioFixtureExporter.export(c.subject(), c.episode(), c.twin(), ++index);
                        ScenarioFixtureExporter.write(f, OUT);
                        bytes += Files.size(OUT.resolve(f.id() + ".json"));
                        written++;
                        any = true;
                    }
                }
                if (!any) break;
            }
        }

        System.out.printf("exported %d fixtures (%d bytes) to %s%n", written, bytes, OUT.toAbsolutePath());
        assertThat(bytes).isLessThanOrEqualTo(ScenarioFixtureGuardTest.MAX_TOTAL_BYTES);
        for (Path file : ScenarioFixtureGuardTest.fixtureFilesIn(OUT)) {
            assertThat(ScenarioFixture.privacyViolations(Files.readString(file))).as(file.toString()).isEmpty();
        }
    }
}
