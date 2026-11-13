package che.glucosemonitorbe.azt1d;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Turns a mined episode into a de-identified {@link ScenarioFixture} and reads and writes fixture files. */
final class ScenarioFixtureExporter {

    private static final long MIN = 60_000L;
    private static final int HISTORY_MIN = 480;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ScenarioFixtureExporter() {}

    /**
     * @param twin  the forecast whose twin scales are stored
     * @param index 1-based position within its kind, used only to name the fixture
     */
    static ScenarioFixture export(Azt1dDataset.Subject s, ScenarioEpisode ep, ForecastResult twin, int index) {
        long start = ep.startEpochMs();
        long from = start - HISTORY_MIN * MIN;
        long to = start + ep.horizonMin() * MIN;

        List<ScenarioFixture.Reading> readings = s.cgm().stream()
                .filter(r -> r.epochMs() >= from && r.epochMs() <= to)
                .sorted(java.util.Comparator.comparingLong(r -> r.epochMs()))
                .map(r -> new ScenarioFixture.Reading((int) ((r.epochMs() - start) / MIN), r.mmol()))
                .toList();
        List<ScenarioFixture.Event> events = s.events().stream()
                .filter(e -> !e.longActing() && e.epochMs() >= from && e.epochMs() <= to)
                .map(e -> new ScenarioFixture.Event((int) ((e.epochMs() - start) / MIN), e.carbs(), e.insulin()))
                .toList();

        LocalDateTime local = LocalDateTime.ofEpochSecond(start / 1000, 0, ZoneOffset.UTC);
        Azt1dDataset.Profile p = s.profile();
        ScenarioFixture draft = new ScenarioFixture(
                ep.kind().name().toLowerCase().replace('_', '-') + String.format("-%02d", index),
                ep.kind(), ep.horizonMin(), local.getHour(), local.getDayOfWeek().getValue(),
                new ScenarioFixture.Profile(p.weightKg(), p.isfMmolPerU(), p.carbRatioGPerU(), p.basalRateUPerH()),
                twin.scales().isfScale(), twin.scales().agScale(), twin.scales().egpScale(), twin.twinApplied(),
                readings, events, null);
        // Recorded from the fixture's own replay, so the file always reproduces its expectation.
        Verdict v = ScenarioRules.evaluate(draft.replay());
        return draft.withExpected(new ScenarioFixture.Expectation(v.outcome(), v.errorMmol()));
    }

    static void write(ScenarioFixture f, Path dir) throws IOException {
        Files.createDirectories(dir);
        JSON.writeValue(dir.resolve(f.id() + ".json").toFile(), f);
    }

    static ScenarioFixture read(Path file) throws IOException {
        return JSON.readValue(file.toFile(), ScenarioFixture.class);
    }
}
