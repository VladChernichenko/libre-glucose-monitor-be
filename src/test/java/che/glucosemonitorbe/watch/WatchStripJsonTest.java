package che.glucosemonitorbe.watch;

import che.glucosemonitorbe.watch.WatchStrip.ActivityEstimate;
import che.glucosemonitorbe.watch.WatchStrip.GlucosePoint;
import che.glucosemonitorbe.watch.WatchStrip.Kind;
import che.glucosemonitorbe.watch.WatchStrip.Reason;
import che.glucosemonitorbe.watch.WatchStrip.State;
import che.glucosemonitorbe.watch.WatchStrip.Suggestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the wire shape iOS decodes ({@code BackendAPI.WatchStrip}), with the app's ObjectMapper config. */
@JsonTest
class WatchStripJsonTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void serializesTheShapeIosDecodes() throws Exception {
        WatchStrip strip = new WatchStrip(State.URGENT_HIGH, Reason.HIGH_FORECAST,
                new Suggestion(Kind.INSULIN, 15, 2.5, "SETTINGS_INVALID", 5.55, 2.2, 5.2),
                new GlucosePoint(LocalDateTime.of(2026, 10, 31, 19, 50), 3.8),
                new GlucosePoint(LocalDateTime.of(2026, 10, 31, 23, 0), 11.0),
                1.5, 12.0, LocalDateTime.of(2026, 10, 31, 20, 0), ActivityEstimate.LEARNED);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(strip));

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "state", "reason", "suggestion", "low", "landing", "iob", "cob", "nextCheckAt", "activityEstimate");
        assertThat(json.get("state").asText()).isEqualTo("URGENT_HIGH");
        assertThat(json.get("reason").asText()).isEqualTo("HIGH_FORECAST");
        assertThat(json.get("activityEstimate").asText()).isEqualTo("LEARNED");
        assertThat(json.get("iob").asDouble()).isEqualTo(1.5);
        assertThat(json.get("cob").asDouble()).isEqualTo(12.0);
        assertThat(json.get("nextCheckAt").asText()).isEqualTo("2026-10-31T20:00:00");

        JsonNode s = json.get("suggestion");
        assertThat(s.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "kind", "grams", "units", "refusalCode", "targetMmol", "isf", "minWithCarbs");
        assertThat(s.get("kind").asText()).isEqualTo("INSULIN");
        assertThat(s.get("grams").asInt()).isEqualTo(15);
        assertThat(s.get("units").asDouble()).isEqualTo(2.5);
        assertThat(s.get("refusalCode").asText()).isEqualTo("SETTINGS_INVALID");
        assertThat(s.get("targetMmol").asDouble()).isEqualTo(5.55);
        assertThat(s.get("isf").asDouble()).isEqualTo(2.2);
        assertThat(s.get("minWithCarbs").asDouble()).isEqualTo(5.2);

        assertThat(json.get("low").fieldNames()).toIterable().containsExactlyInAnyOrder("at", "mmol");
        assertThat(json.get("low").get("at").asText()).isEqualTo("2026-10-31T19:50:00");
        assertThat(json.get("low").get("mmol").asDouble()).isEqualTo(3.8);
        assertThat(json.get("landing").get("at").asText()).isEqualTo("2026-10-31T23:00:00");
        assertThat(json.get("landing").get("mmol").asDouble()).isEqualTo(11.0);
    }

    @Test
    void enumStringsMatchIos() {
        assertThat(List.of(State.values()).stream().map(Enum::name))
                .containsExactlyInAnyOrder("CALM", "URGENT_LOW", "URGENT_HIGH");
        assertThat(List.of(Reason.values()).stream().map(Enum::name)).containsExactlyInAnyOrder(
                "LOW_NOW", "TREATING_LOW", "FORECAST_LOW", "CARBS_LANDING", "HIGH_FORECAST",
                "INSULIN_LANDING", "SMALL_RISE", "WATCHING", "DIP_AHEAD");
        assertThat(List.of(Kind.values()).stream().map(Enum::name))
                .containsExactlyInAnyOrder("CARBS", "INSULIN", "NO_ACTION", "REFUSED");
        assertThat(List.of(ActivityEstimate.values()).stream().map(Enum::name))
                .containsExactlyInAnyOrder("LEARNED", "DEFAULT");
    }
}
