package che.glucosemonitorbe.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Pure per-user 5-minute slot math. Slots are on epoch seconds: floorMod(sec - offset, 300) == 0. */
public final class SyncSlot {

    public static final Duration WINDOW = Duration.ofSeconds(300);
    private static final long WINDOW_SECONDS = 300L;

    private SyncSlot() {
    }

    public static int offsetSeconds(UUID userId) {
        return (int) Math.floorMod(
                userId.getMostSignificantBits() ^ userId.getLeastSignificantBits(), WINDOW_SECONDS);
    }

    /** Smallest instant s >= t (truncated to whole seconds) lying on the user's grid. */
    public static Instant slotAtOrAfter(UUID userId, Instant t) {
        long sec = t.getEpochSecond();
        long offset = offsetSeconds(userId);
        long rem = Math.floorMod(sec - offset, WINDOW_SECONDS);
        long slot = rem == 0 ? sec : sec + (WINDOW_SECONDS - rem);
        return Instant.ofEpochSecond(slot);
    }

    public static Instant followingSlot(UUID userId, Instant dueAt, Instant now) {
        Instant next = slotAtOrAfter(userId, dueAt.plusSeconds(1));
        return next.isBefore(now) ? now : next;
    }

    public static Instant nextFutureSlot(UUID userId, Instant now) {
        return slotAtOrAfter(userId, now.plusSeconds(1));
    }

    public static Instant slotAtLeast300After(UUID userId, Instant t) {
        return slotAtOrAfter(userId, t.plusSeconds(WINDOW_SECONDS));
    }

    public static Instant startupSlot(UUID userId, Instant storedOrNull, Instant now) {
        if (storedOrNull != null && storedOrNull.isAfter(now)) {
            return storedOrNull;
        }
        return nextFutureSlot(userId, now);
    }
}
