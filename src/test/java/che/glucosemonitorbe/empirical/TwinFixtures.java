package che.glucosemonitorbe.empirical;

import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the bundled empirical-twin fixtures ({@code src/test/resources/twin/}).
 *
 * <p>The fixtures are real personal health data and are git-ignored, so a clean checkout does not
 * have them. Every test that needs them is therefore data-gated: {@link #assumePresent()} skips
 * rather than fails when they are absent, the same convention as the AZT1D / HUPA harnesses.</p>
 */
final class TwinFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TwinFixtures() {}

    /** One row of {@code golden-forecasts.json}. */
    record Golden(long anchorEpochMs, int[] minutes, double[] mgdl) {}

    static void assumePresent() {
        Assumptions.assumeTrue(TwinFixtures.class.getResource("/twin/golden-forecasts.json") != null,
                "empirical-twin fixtures are not on the classpath (git-ignored personal data)");
    }

    static JsonNode read(String name) {
        try (InputStream in = TwinFixtures.class.getResourceAsStream("/twin/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read twin fixture " + name, e);
        }
    }

    /** CGM readings, converted to the mmol/L boundary unit with the engine's own factor. */
    static List<PredictionReplayEngine.Reading> readings() {
        List<PredictionReplayEngine.Reading> out = new ArrayList<>();
        for (JsonNode r : read("readings.json")) {
            out.add(new PredictionReplayEngine.Reading(epochMs(r.get("time").asText()),
                    r.get("mgdl").asDouble() / EmpiricalTwinEngine.MGDL_PER_MMOL));
        }
        return out;
    }

    static List<EmpiricalEvent> events() {
        List<EmpiricalEvent> out = new ArrayList<>();
        for (JsonNode e : read("events.json")) {
            OffsetDateTime time = OffsetDateTime.parse(e.get("time").asText());
            out.add(new EmpiricalEvent(
                    type(e.get("type").asText()),
                    time.toInstant().toEpochMilli(),
                    e.hasNonNull("end") ? epochMs(e.get("end").asText()) : null,
                    e.get("amount").asDouble(),
                    time.getHour() + time.getMinute() / 60.0,
                    e.hasNonNull("fat") ? e.get("fat").asDouble() != 0 : null,
                    e.hasNonNull("fruit") ? e.get("fruit").asDouble() != 0 : null,
                    e.hasNonNull("gi") ? e.get("gi").asDouble() : null));
        }
        return out;
    }

    static List<Golden> golden() {
        List<Golden> out = new ArrayList<>();
        for (JsonNode g : read("golden-forecasts.json")) {
            JsonNode f = g.get("forecast");
            int[] minutes = new int[f.size()];
            double[] mgdl = new double[f.size()];
            for (int i = 0; i < f.size(); i++) {
                minutes[i] = f.get(i).get("min").asInt();
                mgdl[i] = f.get(i).get("mgdl").asDouble();
            }
            out.add(new Golden(epochMs(g.get("anchor").asText()), minutes, mgdl));
        }
        return out;
    }

    static long epochMs(String iso) {
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
    }

    private static EmpiricalEvent.Type type(String fixtureType) {
        return switch (fixtureType) {
            case "ins" -> EmpiricalEvent.Type.INSULIN;
            case "basal" -> EmpiricalEvent.Type.BASAL;
            case "meal" -> EmpiricalEvent.Type.MEAL;
            case "fast" -> EmpiricalEvent.Type.FAST;
            case "walk" -> EmpiricalEvent.Type.WALK;
            case "alc" -> EmpiricalEvent.Type.ALCOHOL;
            case "heat" -> EmpiricalEvent.Type.HEAT;
            default -> throw new IllegalArgumentException("unknown fixture event type " + fixtureType);
        };
    }
}
