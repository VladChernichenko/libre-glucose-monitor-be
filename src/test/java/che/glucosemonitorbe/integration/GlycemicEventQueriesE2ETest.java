package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The scan and /prompt load a bounded set of OPEN events in SQL rather than every OPEN row ever written. */
class GlycemicEventQueriesE2ETest extends AbstractGlycemicEventE2ETest {

    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC).withNano(0);

    private GlycemicEvent ev(UUID u, Phase p, State s, LocalDateTime onset, double confidence) {
        return eventRepository.save(GlycemicEvent.builder().userId(u).category(Category.UNLOGGED_FOOD)
                .detectorCategory(Category.UNLOGGED_FOOD).direction(Direction.RISE).phase(p)
                .onsetAt(onset).windowStart(onset).windowEnd(onset.plusMinutes(50))
                .resolvesAt(p == Phase.PROVISIONAL ? onset.plusHours(6) : null)
                .meanResidualMmol(3.0).confidence(confidence).state(s).build());
    }

    @Test
    @DisplayName("scan loads OPEN events inside the look-back, plus every PROVISIONAL one")
    void openForScanIsBounded() {
        UUID u = userId();
        LocalDateTime after = NOW.minusHours(10);
        GlycemicEvent recent = ev(u, Phase.FINAL, State.OPEN, NOW.minusHours(2), 0.6);
        GlycemicEvent oldProvisional = ev(u, Phase.PROVISIONAL, State.OPEN, NOW.minusDays(3), 0.6);
        ev(u, Phase.FINAL, State.OPEN, NOW.minusDays(3), 0.6);                  // old and settled
        ev(u, Phase.FINAL, State.CONFIRMED, NOW.minusHours(2), 0.6);            // answered

        assertThat(eventRepository.findOpenForScan(u, after)).extracting(GlycemicEvent::getId)
                .containsExactlyInAnyOrder(recent.getId(), oldProvisional.getId());
    }

    @Test
    @DisplayName("prompt candidate: OPEN, unsuppressed, top-level, confident, fresh; highest confidence then latest onset")
    void promptCandidateFiltersAndOrdersInSql() {
        UUID u = userId();
        LocalDateTime oldest = NOW.minusHours(6);
        assertThat(eventRepository.findPromptCandidate(u, 0.5, oldest)).isEmpty();

        ev(u, Phase.FINAL, State.OPEN, NOW.minusHours(1), 0.4);                                   // unsure
        GlycemicEvent suppressed = ev(u, Phase.FINAL, State.OPEN, NOW.minusHours(1), 0.95);
        suppressed.setSuppressed(true);
        eventRepository.save(suppressed);
        ev(u, Phase.FINAL, State.OPEN, NOW.minusHours(8), 0.95);                                  // stale
        ev(u, Phase.FINAL, State.DISMISSED, NOW.minusHours(1), 0.95);                             // answered
        ev(u, Phase.FINAL, State.OPEN, NOW.minusHours(3), 0.8);                                   // earlier tie
        GlycemicEvent best = ev(u, Phase.FINAL, State.OPEN, NOW.minusHours(2), 0.8);
        GlycemicEvent child = ev(u, Phase.FINAL, State.OPEN, NOW.minusHours(1), 0.95);
        child.setLinkedEventId(best.getId());
        eventRepository.save(child);

        assertThat(eventRepository.findPromptCandidate(u, 0.5, oldest)).map(GlycemicEvent::getId).contains(best.getId());
    }
}
