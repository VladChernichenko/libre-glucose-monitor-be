package che.glucosemonitorbe.azt1d;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps committed scenario fixtures de-identified: no calendar date, no subject id, and a small total
 * size. {@code -Dscenario.fixtures.dir=...} points it at another folder (for example
 * {@code build/scenario-fixtures} before anything is committed).
 */
class ScenarioFixtureGuardTest {

    static final long MAX_TOTAL_BYTES = 300 * 1024;

    static Path fixturesDir() {
        return Path.of(System.getProperty("scenario.fixtures.dir", "src/test/resources/scenarios/azt1d"));
    }

    static List<Path> fixtureFiles() throws IOException {
        return fixtureFilesIn(fixturesDir());
    }

    static List<Path> fixtureFilesIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void noFixtureCarriesADateOrASubjectId() throws Exception {
        for (Path file : fixtureFiles()) {
            assertThat(ScenarioFixture.privacyViolations(Files.readString(file)))
                    .as("fixture %s", file.getFileName()).isEmpty();
        }
    }

    @Test
    void fixturesStayUnderThreeHundredKilobytes() throws Exception {
        long total = 0;
        for (Path file : fixtureFiles()) total += Files.size(file);

        assertThat(total).isLessThanOrEqualTo(MAX_TOTAL_BYTES);
    }

    @Test
    void theGuardCatchesADate() {
        assertThat(ScenarioFixture.privacyViolations("{\"at\":\"2024-02-11T10:00\"}")).isNotEmpty();
    }

    @Test
    void theGuardCatchesASubjectId() {
        assertThat(ScenarioFixture.privacyViolations("{\"id\":\"Subject 14\"}")).isNotEmpty();
        assertThat(ScenarioFixture.privacyViolations("{\"subjectId\":\"x\"}")).isNotEmpty();
    }

    @Test
    void aCleanFixtureHasNoViolations() {
        assertThat(ScenarioFixture.privacyViolations("{\"id\":\"correction-drop-03\",\"hourOfDay\":14}")).isEmpty();
    }

    @Test
    void theGuardCatchesAnEpochTimestamp() {
        assertThat(ScenarioFixture.privacyViolations("{\"t\":1704110400000}")).isNotEmpty();
        assertThat(ScenarioFixture.privacyViolations("{\"t\":1704110400}")).isNotEmpty();
        assertThat(ScenarioFixture.privacyViolations("{\"mmol\":12.987012987012987,\"minute\":-480}")).isEmpty();
    }

    @Test
    void onlyJsonFilesMayLiveInTheFixtureFolder(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ok.json"), "{}");
        assertThat(strayFiles(dir)).isEmpty();

        Files.writeString(dir.resolve("export.csv"), "a,b");

        assertThat(strayFiles(dir)).containsExactly("export.csv");
    }

    @Test
    void theRealFixtureFolderHoldsOnlyJsonFiles() throws Exception {
        assertThat(strayFiles(fixturesDir())).isEmpty();
    }

    /** Names of files in {@code dir} that are not fixtures. */
    static List<String> strayFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> !n.endsWith(".json")).sorted().toList();
        }
    }
}
