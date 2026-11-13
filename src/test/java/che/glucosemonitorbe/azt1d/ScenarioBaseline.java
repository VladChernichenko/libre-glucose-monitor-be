package che.glucosemonitorbe.azt1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Today's results per scenario kind and subject, kept in a local file so later runs fail only on a
 * regression, never on a gap that was already there. An entry holds the median forecast error
 * (mmol/L at 120 minutes) and the number of episodes that failed their rule.
 */
final class ScenarioBaseline {

    /** A median error may grow by this factor and {@link #ABSOLUTE_MARGIN} before it counts as a regression. */
    static final double REGRESSION_FACTOR = 1.15;
    static final double ABSOLUTE_MARGIN = 0.2;

    record Entry(double medianErrorMmol, int failCount) {}

    /** The file's shape: per kind-and-subject cells, and the episodes that passed when it was recorded. */
    private record Stored(Map<String, Entry> cells, List<String> passing) {}

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Map<String, Entry> entries = new TreeMap<>();
    private final java.util.Set<String> passing = new java.util.TreeSet<>();

    static String key(ScenarioKind kind, String subjectId) {
        return kind.name() + "|" + subjectId;
    }

    /** One episode: the baseline is local and never committed, so its start time may be part of the key. */
    static String episodeKey(ScenarioKind kind, String subjectId, long startEpochMs) {
        return key(kind, subjectId) + "|" + startEpochMs;
    }

    void addPassing(String episodeKey) {
        passing.add(episodeKey);
    }

    List<String> passingEpisodes() {
        return List.copyOf(passing);
    }

    /** {@code -Dazt1d.baseline}, else a file under the user's home so a {@code gradle clean} cannot erase it. */
    static Path defaultPath() {
        String configured = System.getProperty("azt1d.baseline");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        return Path.of(System.getProperty("user.home"), ".glucose-monitor-test", "azt1d-scenario-baseline.json");
    }

    void put(String key, double medianErrorMmol, int failCount) {
        entries.put(key, new Entry(medianErrorMmol, failCount));
    }

    Optional<Entry> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    Map<String, Entry> entries() {
        return Map.copyOf(entries);
    }

    /** Empty when the file does not exist; an exception naming the path when it exists but cannot be read. */
    static Optional<ScenarioBaseline> load(Path file) {
        if (!Files.exists(file)) return Optional.empty();
        try {
            ScenarioBaseline b = new ScenarioBaseline();
            Stored stored = JSON.readValue(file.toFile(), Stored.class);
            b.entries.putAll(stored.cells());
            b.passing.addAll(stored.passing());
            return Optional.of(b);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unreadable scenario baseline " + file
                    + " - fix or delete it (or pass -Dazt1d.baseline.update=true)", e);
        }
    }

    void save(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        JSON.writeValue(file.toFile(), new Stored(entries, List.copyOf(passing)));
    }

    /**
     * What got worse in {@code current} compared with this baseline: a recorded cell the current run no
     * longer produces, more failing episodes, a median error beyond the margin, or an episode that
     * passed when recorded and does not pass now. Cells and episodes this baseline never recorded are
     * not compared, so a newly passing episode is never a regression. A recorded median of NaN (no
     * error samples) is not compared on error.
     */
    List<String> regressions(ScenarioBaseline current) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry now = current.entries.get(e.getKey());
            Entry was = e.getValue();
            if (now == null) {
                out.add(String.format("%s: no longer produced by this run (baseline recorded %d failing episodes)",
                        e.getKey(), was.failCount()));
                continue;
            }
            if (now.failCount() > was.failCount()) {
                out.add(String.format("%s: %d failing episodes, baseline %d",
                        e.getKey(), now.failCount(), was.failCount()));
            }
            double limit = was.medianErrorMmol() * REGRESSION_FACTOR + ABSOLUTE_MARGIN;
            if (!Double.isNaN(was.medianErrorMmol()) && now.medianErrorMmol() > limit) {
                out.add(String.format("%s: median error %.2f above the allowed %.2f (baseline %.2f)",
                        e.getKey(), now.medianErrorMmol(), limit, was.medianErrorMmol()));
            }
        }
        for (String episode : passing) {
            if (!current.passing.contains(episode)) {
                out.add(episode + ": passed when recorded, does not pass now (or is no longer mined)");
            }
        }
        return out;
    }
}
