package che.glucosemonitorbe.azt1d;

import com.fasterxml.jackson.core.type.TypeReference;
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

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Map<String, Entry> entries = new TreeMap<>();

    static String key(ScenarioKind kind, String subjectId) {
        return kind.name() + "|" + subjectId;
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
            b.entries.putAll(JSON.readValue(file.toFile(), new TypeReference<Map<String, Entry>>() {}));
            return Optional.of(b);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unreadable scenario baseline " + file
                    + " - fix or delete it (or pass -Dazt1d.baseline.update=true)", e);
        }
    }

    void save(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        JSON.writeValue(file.toFile(), entries);
    }

    /** What got worse in {@code current} compared with this baseline; entries this baseline never recorded are not compared. */
    List<String> regressions(ScenarioBaseline current) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : current.entries.entrySet()) {
            Entry was = entries.get(e.getKey());
            if (was == null) continue;
            Entry now = e.getValue();
            if (now.failCount() > was.failCount()) {
                out.add(String.format("%s: %d failing episodes, baseline %d",
                        e.getKey(), now.failCount(), was.failCount()));
            }
            double limit = was.medianErrorMmol() * REGRESSION_FACTOR + ABSOLUTE_MARGIN;
            if (now.medianErrorMmol() > limit) {
                out.add(String.format("%s: median error %.2f above the allowed %.2f (baseline %.2f)",
                        e.getKey(), now.medianErrorMmol(), limit, was.medianErrorMmol()));
            }
        }
        return out;
    }
}
