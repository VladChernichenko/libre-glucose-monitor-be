package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.EventFeaturesBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventTailE2ETest extends AbstractGlycemicEventE2ETest {

    private static final long H = 3_600_000L, MIN = 60_000L;

    private GlycemicEvent provisional(UUID u, Direction dir, Category cat, LocalDateTime onset,
                                      LocalDateTime windowEnd, LocalDateTime resolvesAt) {
        return eventRepository.save(GlycemicEvent.builder().userId(u).category(cat).detectorCategory(cat)
                .direction(dir).phase(Phase.PROVISIONAL).onsetAt(onset).windowStart(onset).windowEnd(windowEnd)
                .resolvesAt(resolvesAt).meanResidualMmol(dir == Direction.RISE ? 3.0 : -3.0).sigmaMultiple(4.0)
                .confidence(0.5).features(new EventFeaturesBuilder().build()).state(State.OPEN).build());
    }

    private String lastReason(UUID eventId) {
        List<GlycemicEventHistory> h = historyRepository.findByEventIdOrderByAtAsc(eventId);
        return h.get(h.size() - 1).getReason();
    }

    @Test
    @DisplayName("a delayed drop after resistance resolves ABSORPTION_DELAY and links the drop, with no UNLOGGED_INSULIN")
    void slingshot() {
        UUID u = userId();
        long now = nowMs();
        seed(u, now - 47 * STEP, concat(repeat(36, 200), repeat(12, 90)));
        GlycemicEvent parent = provisional(u, Direction.RISE, Category.STRESS,
                toLdt(now - 3 * H), toLdt(now - 2 * H), toLdt(now + 3 * H));

        scanService.scanUserEvents(u);

        GlycemicEvent p = eventRepository.findById(parent.getId()).orElseThrow();
        assertThat(p.getCategory()).isEqualTo(Category.ABSORPTION_DELAY);
        assertThat(p.getPhase()).isEqualTo(Phase.FINAL);
        assertThat(p.getResolvesAt()).isNull();
        assertThat(eventRepository.findByLinkedEventId(p.getId()))
                .singleElement().satisfies(c -> assertThat(c.getDirection()).isEqualTo(Direction.FALL));
        assertThat(eventRepository.findAll()).noneMatch(e -> e.getCategory() == Category.UNLOGGED_INSULIN);
        assertThat(lastReason(p.getId())).isEqualTo("tail:slingshot");
    }

    @Test
    @DisplayName("a slingshot adopts an earlier standalone drop as the linked child instead of duplicating it")
    void slingshotAdoptsAnEarlierStandaloneDrop() {
        UUID u = userId();
        long now = nowMs();
        seed(u, now - 47 * STEP, concat(repeat(36, 200), repeat(12, 90)));
        GlycemicEvent parent = provisional(u, Direction.RISE, Category.STRESS,
                toLdt(now - 3 * H), toLdt(now - 2 * H), toLdt(now + 3 * H));
        GlycemicEvent standalone = provisional(u, Direction.FALL, Category.UNLOGGED_INSULIN,
                toLdt(now - 55 * MIN), toLdt(now - 10 * MIN), toLdt(now + 60 * MIN));

        scanService.scanUserEvents(u);

        GlycemicEvent p = eventRepository.findById(parent.getId()).orElseThrow();
        assertThat(p.getCategory()).isEqualTo(Category.ABSORPTION_DELAY);
        assertThat(eventRepository.findByLinkedEventId(p.getId()))
                .singleElement().satisfies(c -> assertThat(c.getId()).isEqualTo(standalone.getId()));
        assertThat(eventRepository.findAll()).noneMatch(e -> e.getCategory() == Category.UNLOGGED_INSULIN);
    }

    @Test
    @DisplayName("insulin with no measurable effect over its full action resolves LIPODYSTROPHY")
    void lipodystrophy() {
        UUID u = userId();
        long now = nowMs();
        long onset = now - 390 * MIN;
        note(u, toLdt(onset - 15 * MIN), 0, 5.0, Note.TYPE_NORMAL);
        int n = 79;
        int[] sgv = new int[n];
        for (int i = 0; i < n; i++) sgv[i] = 180 - (int) Math.round(9.0 * i / (n - 1));   // 10.0 -> 9.5 mmol/L
        seed(u, onset, sgv);
        GlycemicEvent parent = provisional(u, Direction.RISE, Category.STRESS,
                toLdt(onset), toLdt(onset + H), toLdt(now - 10 * MIN));

        scanService.scanUserEvents(u);

        GlycemicEvent p = eventRepository.findById(parent.getId()).orElseThrow();
        assertThat(p.getCategory()).isEqualTo(Category.LIPODYSTROPHY);
        assertThat(p.getFeatures().insulinEffectRatio()).isBetween(0.0, 0.25);
        assertThat(p.getConfidence()).isEqualTo(0.4);
        assertThat(lastReason(p.getId())).isEqualTo("tail:no-effect");
    }

    @Test
    @DisplayName("glucose rising beyond the no-insulin curve resolves STRESS")
    void stress() {
        UUID u = userId();
        long now = nowMs();
        long onset = now - 390 * MIN;
        note(u, toLdt(onset - 15 * MIN), 0, 5.0, Note.TYPE_NORMAL);
        int n = 79;
        int[] sgv = new int[n];
        for (int i = 0; i < n; i++) sgv[i] = 180 + (int) Math.round(90.0 * i / (n - 1));  // 10 -> 15 mmol/L
        seed(u, onset, sgv);
        GlycemicEvent parent = provisional(u, Direction.RISE, Category.STRESS,
                toLdt(onset), toLdt(onset + H), toLdt(now - 10 * MIN));

        scanService.scanUserEvents(u);

        GlycemicEvent p = eventRepository.findById(parent.getId()).orElseThrow();
        assertThat(p.getCategory()).isEqualTo(Category.STRESS);
        assertThat(p.getPhase()).isEqualTo(Phase.FINAL);
        assertThat(lastReason(p.getId())).isEqualTo("tail:stress");
    }

    @Test
    @DisplayName("a sensor gap over the tail never resolves LIPODYSTROPHY - STRESS with insufficient-data")
    void insufficientTailData() {
        UUID u = userId();
        long now = nowMs();
        GlycemicEvent parent = provisional(u, Direction.RISE, Category.STRESS,
                toLdt(now - 390 * MIN), toLdt(now - 330 * MIN), toLdt(now - 10 * MIN));

        scanService.scanUserEvents(u);

        GlycemicEvent p = eventRepository.findById(parent.getId()).orElseThrow();
        assertThat(p.getCategory()).isEqualTo(Category.STRESS);
        assertThat(lastReason(p.getId())).isEqualTo("tail:insufficient-data");
    }

    @Test
    @DisplayName("an unexplained drop that stopped within 90 min resolves AEROBIC_ACTIVITY")
    void dropStopped() {
        UUID u = userId();
        long now = nowMs();
        GlycemicEvent e = provisional(u, Direction.FALL, Category.UNLOGGED_INSULIN,
                toLdt(now - 130 * MIN), toLdt(now - 80 * MIN), toLdt(now - 10 * MIN));
        scanService.scanUserEvents(u);
        assertThat(eventRepository.findById(e.getId()).orElseThrow().getCategory()).isEqualTo(Category.AEROBIC_ACTIVITY);
    }

    @Test
    @DisplayName("an unexplained drop still running at resolution resolves UNLOGGED_INSULIN (final)")
    void dropContinued() {
        UUID u = userId();
        long now = nowMs();
        GlycemicEvent e = provisional(u, Direction.FALL, Category.UNLOGGED_INSULIN,
                toLdt(now - 130 * MIN), toLdt(now - 5 * MIN), toLdt(now - 10 * MIN));
        scanService.scanUserEvents(u);
        GlycemicEvent back = eventRepository.findById(e.getId()).orElseThrow();
        assertThat(back.getCategory()).isEqualTo(Category.UNLOGGED_INSULIN);
        assertThat(back.getPhase()).isEqualTo(Phase.FINAL);
    }

    @Test
    @DisplayName("a short spike followed by a prompt drop is ANAEROBIC_ACTIVITY with the drop linked")
    void anaerobic() {
        UUID u = userId();
        seed(u, nowMs() - 47 * STEP, concat(repeat(14, 110), repeat(11, 200), repeat(12, 60), repeat(11, 110)));

        scanService.scanUserEvents(u);

        GlycemicEvent rise = eventRepository.findAll().stream()
                .filter(e -> e.getDirection() == Direction.RISE && e.getLinkedEventId() == null).findFirst().orElseThrow();
        assertThat(rise.getCategory()).isEqualTo(Category.ANAEROBIC_ACTIVITY);
        assertThat(eventRepository.findByLinkedEventId(rise.getId())).hasSize(1);
        assertThat(eventRepository.findAll()).noneMatch(e -> e.getCategory() == Category.UNLOGGED_INSULIN);
    }
}
