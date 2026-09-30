package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventInsightsE2ETest extends AbstractGlycemicEventE2ETest {

    private void ev(UUID u, Category c, LocalDateTime onset, State s, Category userLabel) {
        eventRepository.save(GlycemicEvent.builder().userId(u).category(userLabel != null ? userLabel : c)
                .detectorCategory(c).userLabel(userLabel).direction(c.direction())
                .onsetAt(onset).windowStart(onset).windowEnd(onset.plusMinutes(50)).meanResidualMmol(2.0)
                .state(s).build());
    }

    @Test
    void countsHoursAgreementAndRecurrence() throws Exception {
        UUID u = userId();
        LocalDateTime today10 = LocalDateTime.now(ZoneOffset.UTC).withHour(10).withMinute(0).withSecond(0).withNano(0).minusDays(1);
        ev(u, Category.STRESS, today10, State.CONFIRMED, null);                          // agreed
        ev(u, Category.STRESS, today10.minusDays(1), State.CONFIRMED, Category.UNLOGGED_FOOD); // relabelled
        ev(u, Category.LIPODYSTROPHY, today10.minusDays(2), State.OPEN, null);
        ev(u, Category.LIPODYSTROPHY, today10.minusDays(3), State.OPEN, null);

        ResponseEntity<String> resp = rest.exchange("/api/glycemic-events/insights?days=30", HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class);
        JsonNode body = mapper.readTree(resp.getBody());

        assertThat(body.path("counts").path("LIPODYSTROPHY").asInt()).isEqualTo(2);
        assertThat(body.path("counts").path("STRESS").asInt()).isEqualTo(1);
        assertThat(body.path("counts").path("UNLOGGED_FOOD").asInt()).isEqualTo(1);
        assertThat(body.path("hourHistogram").path("STRESS").get(10).asInt()).isEqualTo(1);
        assertThat(body.path("noEffectCount14d").asInt()).isEqualTo(2);
        assertThat(body.path("agreementRate").path("STRESS").asDouble()).isEqualTo(0.5);

        assertThat(rest.exchange("/api/glycemic-events/insights?days=91", HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
