package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventPipelineE2ETest extends AbstractGlycemicEventE2ETest {

    private long seedStepRise(UUID u) {
        long start = nowMs() - 35 * STEP;
        seed(u, start, concat(repeat(24, 110), repeat(12, 200)));
        return start + 24 * STEP;
    }

    @Test
    @DisplayName("onset is marked where the ramp starts, not where it crosses the threshold")
    void onsetAtRampStart() {
        UUID u = userId();
        long start = nowMs() - 35 * STEP;
        seed(u, start, concat(repeat(24, 110), new int[]{120, 130, 140, 150, 160, 170, 180, 190, 200, 210, 220, 230}));
        GlycemicEvent e = scanService.scanUser(u).orElseThrow();
        assertThat(e.getCategory()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(ms(e.getOnsetAt())).isLessThanOrEqualTo(start + 24 * STEP);
        assertThat(ms(e.getOnsetAt())).isGreaterThanOrEqualTo(start + 21 * STEP);
    }

    @Test
    @DisplayName("a rise and a later separate fall become two events")
    void twoRunsTwoEvents() {
        UUID u = userId();
        seed(u, nowMs() - 47 * STEP, concat(repeat(14, 150), repeat(11, 230), repeat(12, 150), repeat(11, 70)));
        List<GlycemicEvent> events = scanService.scanUserEvents(u);
        assertThat(events).extracting(GlycemicEvent::getDirection).containsExactly(Direction.RISE, Direction.FALL);
    }

    @Test
    @DisplayName("a window the user already answered is not re-created as a new OPEN event")
    void answeredWindowNotRecreated() {
        UUID u = userId();
        seedStepRise(u);
        GlycemicEvent e = scanService.scanUser(u).orElseThrow();
        e.setState(State.DISMISSED);
        eventRepository.save(e);

        assertThat(scanService.scanUserEvents(u)).isEmpty();
        assertThat(eventRepository.findByUserIdAndStateIn(u, List.of(State.OPEN))).isEmpty();
    }

    @Test
    @DisplayName("a rise under significant active insulin is provisional Resistance (STRESS)")
    void riseUnderActiveInsulinIsProvisionalStress() {
        UUID u = userId();
        long riseAt = seedStepRise(u);
        note(u, toLdt(riseAt - 30 * 60_000L), 0, 5.0, Note.TYPE_NORMAL);
        GlycemicEvent e = scanService.scanUserEvents(u).stream()
                .filter(x -> x.getDirection() == Direction.RISE).findFirst().orElseThrow();
        assertThat(e.getCategory()).isEqualTo(Category.STRESS);
        assertThat(e.getPhase()).isEqualTo(Phase.PROVISIONAL);
        assertThat(e.getResolvesAt()).isAfter(e.getOnsetAt());
        assertThat(e.getFeatures().expectedDrop60()).isGreaterThanOrEqualTo(1.5);
    }

    @Test
    @DisplayName("detection writes one DETECTED history row with the rule that fired")
    void historyOnDetection() {
        UUID u = userId();
        seedStepRise(u);
        GlycemicEvent e = scanService.scanUser(u).orElseThrow();
        List<GlycemicEventHistory> h = historyRepository.findByEventIdOrderByAtAsc(e.getId());
        assertThat(h).singleElement().satisfies(r -> {
            assertThat(r.getKind()).isEqualTo(GlycemicEventHistory.Kind.DETECTED);
            assertThat(r.getReason()).isEqualTo("rule:R5");
        });
    }

    @Test
    @DisplayName("logging carbs afterwards reclassifies the same event and records UPDATED")
    void laterLogReclassifies() {
        UUID u = userId();
        long riseAt = seedStepRise(u);
        GlycemicEvent first = scanService.scanUser(u).orElseThrow();
        note(u, toLdt(riseAt), 20.0, 0, Note.TYPE_NORMAL);

        GlycemicEvent again = scanService.scanUser(u).orElseThrow();
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getCategory()).isEqualTo(Category.UNDER_ESTIMATED_FOOD);
        assertThat(historyRepository.findByEventIdOrderByAtAsc(first.getId()))
                .extracting(GlycemicEventHistory::getKind)
                .containsExactly(GlycemicEventHistory.Kind.DETECTED, GlycemicEventHistory.Kind.UPDATED);
    }
}
