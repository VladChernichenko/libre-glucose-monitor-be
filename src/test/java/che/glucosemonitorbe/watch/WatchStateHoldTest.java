package che.glucosemonitorbe.watch;

import che.glucosemonitorbe.watch.WatchStrip.ActivityEstimate;
import che.glucosemonitorbe.watch.WatchStrip.Kind;
import che.glucosemonitorbe.watch.WatchStrip.Reason;
import che.glucosemonitorbe.watch.WatchStrip.State;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WatchStateHoldTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 31, 19, 0);

    private final WatchStateHold hold = new WatchStateHold();

    private static WatchStrip strip(State state) {
        return strip(state, Kind.NO_ACTION, T0.plusMinutes(60));
    }

    private static WatchStrip strip(State state, Kind kind, LocalDateTime nextCheckAt) {
        Reason reason = switch (state) {
            case URGENT_LOW -> Reason.LOW_NOW;
            case URGENT_HIGH -> Reason.HIGH_FORECAST;
            case CALM -> Reason.WATCHING;
        };
        return new WatchStrip(state, reason, new WatchStrip.Suggestion(kind, null, null, null, null, null, null),
                null, null, null, null, nextCheckAt, ActivityEstimate.DEFAULT);
    }

    @Test
    void calmHeldForTwoNewReadings() {
        WatchStrip calm = strip(State.CALM);
        assertThat(hold.apply(USER, T0, T0, calm)).contains(calm);
        assertThat(hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), null)).contains(calm);
        assertThat(hold.apply(USER, T0.plusMinutes(10), T0.plusMinutes(10), null)).isEmpty();
    }

    @Test
    void samePollingReading_doesNotCount() {
        WatchStrip calm = strip(State.CALM);
        hold.apply(USER, T0, T0, calm);
        for (int i = 0; i < 5; i++) {
            assertThat(hold.apply(USER, T0, T0, null)).contains(calm);
        }
        assertThat(hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), null)).contains(calm);
    }

    @Test
    void higherPriorityReplacesAtOnce() {
        WatchStrip high = strip(State.URGENT_HIGH);
        hold.apply(USER, T0, T0, strip(State.CALM));
        assertThat(hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), high)).contains(high);
    }

    @Test
    void lowerPriorityDoesNotReplaceHeldHigh() {
        WatchStrip high = strip(State.URGENT_HIGH);
        hold.apply(USER, T0, T0, high);
        assertThat(hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), strip(State.CALM))).contains(high);
    }

    @Test
    void lowClearsHold() {
        WatchStrip low = strip(State.URGENT_LOW);
        hold.apply(USER, T0, T0, strip(State.URGENT_HIGH));
        assertThat(hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), low)).contains(low);
        assertThat(hold.apply(USER, T0.plusMinutes(10), T0.plusMinutes(10), null)).isEmpty();
    }

    @Test
    void gapOverMaxAge_dropsHold() {
        hold.apply(USER, T0, T0, strip(State.CALM));
        assertThat(hold.apply(USER, T0.plusMinutes(16), T0.plusMinutes(16), null)).isEmpty();
    }

    @Test
    void heldDoseStrip_isNeverHeld_weakerCandidateWins() {
        WatchStrip calm = strip(State.CALM);
        hold.apply(USER, T0, T0, strip(State.URGENT_HIGH, Kind.INSULIN, T0.plusMinutes(60)));
        assertThat(hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), calm)).contains(calm);
    }

    @Test
    void heldDoseStrip_isNeverHeld_nullOnNewReadingIsEmpty() {
        hold.apply(USER, T0, T0, strip(State.URGENT_HIGH, Kind.INSULIN, T0.plusMinutes(60)));
        assertThat(hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), null)).isEmpty();
    }

    @Test
    void heldDoseStrip_isNeverHeld_nullOnSameReadingIsEmpty() {
        hold.apply(USER, T0, T0, strip(State.URGENT_HIGH, Kind.INSULIN, T0.plusMinutes(60)));
        assertThat(hold.apply(USER, T0, T0, null)).isEmpty();
    }

    @Test
    void refusedStripIsHeld() {
        WatchStrip refused = strip(State.URGENT_HIGH, Kind.REFUSED, T0.plusMinutes(60));
        hold.apply(USER, T0, T0, refused);
        assertThat(hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), null)).contains(refused);
    }

    @Test
    void heldStrip_nextCheckIsAtLeastFifteenMinutesOut() {
        WatchStrip calm = strip(State.CALM, Kind.NO_ACTION, T0.plusMinutes(10));
        hold.apply(USER, T0, T0, calm);
        WatchStrip held = hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), null).orElseThrow();
        assertThat(held.nextCheckAt()).isEqualTo(T0.plusMinutes(20));
        assertThat(held.state()).isEqualTo(State.CALM);
    }

    @Test
    void heldStrip_laterNextCheckKept() {
        WatchStrip calm = strip(State.CALM);
        hold.apply(USER, T0, T0, calm);
        assertThat(hold.apply(USER, T0.plusMinutes(5), T0.plusMinutes(5), null).orElseThrow().nextCheckAt())
                .isEqualTo(T0.plusMinutes(60));
    }
}
