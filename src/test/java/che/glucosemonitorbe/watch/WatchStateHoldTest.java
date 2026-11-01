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
        Reason reason = switch (state) {
            case URGENT_LOW -> Reason.LOW_NOW;
            case URGENT_HIGH -> Reason.HIGH_FORECAST;
            case CALM -> Reason.WATCHING;
        };
        return new WatchStrip(state, reason, new WatchStrip.Suggestion(Kind.NO_ACTION, null, null, null, null, null, null),
                null, null, null, null, T0.plusMinutes(60), ActivityEstimate.DEFAULT);
    }

    @Test
    void calmHeldForTwoNewReadings() {
        WatchStrip calm = strip(State.CALM);
        assertThat(hold.apply(USER, T0, calm)).contains(calm);
        assertThat(hold.apply(USER, T0.plusMinutes(5), null)).contains(calm);
        assertThat(hold.apply(USER, T0.plusMinutes(10), null)).isEmpty();
    }

    @Test
    void samePollingReading_doesNotCount() {
        WatchStrip calm = strip(State.CALM);
        hold.apply(USER, T0, calm);
        for (int i = 0; i < 5; i++) {
            assertThat(hold.apply(USER, T0, null)).contains(calm);
        }
        assertThat(hold.apply(USER, T0.plusMinutes(5), null)).contains(calm);
    }

    @Test
    void higherPriorityReplacesAtOnce() {
        WatchStrip high = strip(State.URGENT_HIGH);
        hold.apply(USER, T0, strip(State.CALM));
        assertThat(hold.apply(USER, T0.plusMinutes(5), high)).contains(high);
    }

    @Test
    void lowerPriorityDoesNotReplaceHeldHigh() {
        WatchStrip high = strip(State.URGENT_HIGH);
        hold.apply(USER, T0, high);
        assertThat(hold.apply(USER, T0.plusMinutes(5), strip(State.CALM))).contains(high);
    }

    @Test
    void lowClearsHold() {
        WatchStrip low = strip(State.URGENT_LOW);
        hold.apply(USER, T0, strip(State.URGENT_HIGH));
        assertThat(hold.apply(USER, T0.plusMinutes(5), low)).contains(low);
        assertThat(hold.apply(USER, T0.plusMinutes(10), null)).isEmpty();
    }

    @Test
    void gapOverMaxAge_dropsHold() {
        hold.apply(USER, T0, strip(State.CALM));
        assertThat(hold.apply(USER, T0.plusMinutes(16), null)).isEmpty();
    }
}
