package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.ConfirmGlycemicEventRequest;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.UserSettings;
import che.glucosemonitorbe.repository.UserSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventApiE2ETest extends AbstractGlycemicEventE2ETest {

    @Autowired UserSettingsRepository userSettingsRepository;

    private GlycemicEvent event(UUID u, Category c, Phase p, LocalDateTime onset, double confidence) {
        return eventRepository.save(GlycemicEvent.builder().userId(u).category(c).detectorCategory(c)
                .direction(c.direction()).phase(p).onsetAt(onset).windowStart(onset).windowEnd(onset.plusMinutes(50))
                .resolvesAt(p == Phase.PROVISIONAL ? onset.plusHours(6) : null)
                .meanResidualMmol(c.direction() == Direction.RISE ? 3.0 : -3.0).confidence(confidence)
                .state(State.OPEN).build());
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders), String.class);
    }

    private static LocalDateTime utcNow() { return LocalDateTime.now(ZoneOffset.UTC).withNano(0); }

    @Test
    @DisplayName("range list returns intersecting events; ranges over 31 days are 422")
    void rangeList() throws Exception {
        UUID u = userId();
        GlycemicEvent e = event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(2), 0.6);
        ResponseEntity<String> ok = get("/api/glycemic-events?from=" + utcNow().minusHours(3) + "&to=" + utcNow());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode arr = mapper.readTree(ok.getBody());
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).path("id").asText()).isEqualTo(e.getId().toString());
        assertThat(arr.get(0).path("onsetAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(arr.get(0).path("displayLabel").asText()).isEqualTo("Unaccounted carbs");

        assertThat(get("/api/glycemic-events?from=" + utcNow().minusDays(40) + "&to=" + utcNow()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("prompt picks the most confident fresh top-level event; 204 when none qualifies")
    void prompt() throws Exception {
        UUID u = userId();
        assertThat(get("/api/glycemic-events/prompt").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(1), 0.4);               // too unsure
        GlycemicEvent suppressed = event(u, Category.STRESS, Phase.FINAL, utcNow().minusHours(1), 0.9);
        suppressed.setSuppressed(true);
        eventRepository.save(suppressed);
        event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(8), 0.9);               // stale
        GlycemicEvent best = event(u, Category.STRESS, Phase.PROVISIONAL, utcNow().minusHours(2), 0.7);
        eventRepository.save(GlycemicEvent.builder().userId(u).category(Category.STRESS).direction(Direction.FALL)
                .linkedEventId(best.getId()).windowStart(utcNow()).windowEnd(utcNow()).meanResidualMmol(-2.0)
                .confidence(0.95).state(State.OPEN).build());                                   // child

        JsonNode body = mapper.readTree(get("/api/glycemic-events/prompt").getBody());
        assertThat(body.path("id").asText()).isEqualTo(best.getId().toString());
        assertThat(body.path("displayLabel").asText()).isEqualTo("Resistance?");
        assertThat(body.path("promptOptions")).hasSize(4);
    }

    @Test
    @DisplayName("prompt age uses the user's clock (UTC+4)")
    void promptUsesUserClock() {
        UUID u = userId();
        ZoneId tbilisi = ZoneId.of("Asia/Tbilisi");
        UserSettings s = userSettingsRepository.findByUserId(u).orElseGet(() -> {
            UserSettings fresh = new UserSettings();
            fresh.setUserId(u);
            return fresh;
        });
        s.setTimezone(tbilisi.getId());
        s.setUtcOffsetMinutes(240);
        userSettingsRepository.save(s);
        LocalDateTime localNow = LocalDateTime.now(tbilisi).withNano(0);

        GlycemicEvent old = event(u, Category.UNLOGGED_FOOD, Phase.FINAL, localNow.minusHours(7), 0.9);
        assertThat(get("/api/glycemic-events/prompt").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        eventRepository.delete(old);
        event(u, Category.UNLOGGED_FOOD, Phase.FINAL, localNow.minusHours(5), 0.9);
        assertThat(get("/api/glycemic-events/prompt").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("relabel via API records RELABELLED and a history timeline")
    void relabelAndHistory() throws Exception {
        UUID u = userId();
        GlycemicEvent e = event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(1), 0.6);
        ResponseEntity<String> resp = post("/api/glycemic-events/" + e.getId() + "/confirm",
                new ConfirmGlycemicEventRequest("STRESS", null, null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(resp.getBody()).path("userLabel").asText()).isEqualTo("STRESS");

        JsonNode h = mapper.readTree(get("/api/glycemic-events/" + e.getId() + "/history").getBody());
        assertThat(h.get(h.size() - 1).path("kind").asText()).isEqualTo("RELABELLED");
        assertThat(h.get(h.size() - 1).path("actor").asText()).isEqualTo("USER");
    }

    @Test
    @DisplayName("confirm validation over HTTP: 422 wrong direction, 422 provisional without category, 404 foreign history")
    void validation() {
        UUID u = userId();
        GlycemicEvent rise = event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(1), 0.6);
        assertThat(post("/api/glycemic-events/" + rise.getId() + "/confirm",
                new ConfirmGlycemicEventRequest("AEROBIC_ACTIVITY", null, null)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        GlycemicEvent prov = event(u, Category.STRESS, Phase.PROVISIONAL, utcNow().minusHours(1), 0.6);
        assertThat(post("/api/glycemic-events/" + prov.getId() + "/confirm",
                new ConfirmGlycemicEventRequest(null, null, null)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        HttpHeaders other = registerAndLogin();
        assertThat(rest.exchange("/api/glycemic-events/" + rise.getId() + "/history", HttpMethod.GET,
                new HttpEntity<>(other), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
