package che.glucosemonitorbe.watch;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.watch.WatchStrip.State;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No-flicker hold for the watch strip's HIGH and CALM states: once shown, a strip stays until two
 * consecutive NEW stored CGM readings no longer produce it (or anything of equal or higher
 * priority). Counted per stored reading time, not per API call, so the dashboard polling the same
 * reading twice never counts twice. URGENT_LOW is never held here: lows have their own hold and
 * hysteresis, and a low clears this hold.
 *
 * <p>Priority: URGENT_LOW &gt; URGENT_HIGH &gt; CALM. In memory, per user: a restart drops the hold.
 */
@Component
public class WatchStateHold {

    /** Consecutive new weaker readings that end the hold. */
    static final int CLEARING_READINGS = 2;

    private record Held(WatchStrip strip, LocalDateTime readingTime, int clearStreak) {}

    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    /**
     * @param readingTime the stored CGM reading time this candidate was computed from
     * @param candidate   what the rules produced for that reading, or null for no strip
     * @return the strip to show: the candidate, or the held strip while the hold lasts
     */
    public Optional<WatchStrip> apply(UUID userId, LocalDateTime readingTime, @Nullable WatchStrip candidate) {
        if (candidate != null && candidate.state() == State.URGENT_LOW) {
            held.remove(userId);
            return Optional.of(candidate);
        }
        Held after = held.compute(userId, (id, h) -> {
            if (candidate != null && (h == null || priority(candidate) >= priority(h.strip()))) {
                return new Held(candidate, readingTime, 0);
            }
            if (h == null) return null;
            boolean newReading = readingTime != null
                    && (h.readingTime() == null || readingTime.isAfter(h.readingTime()));
            if (!newReading) return h;
            // A held strip from before a data gap describes a forecast that no longer applies.
            if (h.readingTime() != null
                    && Duration.between(h.readingTime(), readingTime).compareTo(CgmFreshnessService.MAX_AGE) > 0) {
                return null;
            }
            int streak = h.clearStreak() + 1;
            return streak >= CLEARING_READINGS ? null : new Held(h.strip(), readingTime, streak);
        });
        return after == null ? Optional.ofNullable(candidate) : Optional.of(after.strip());
    }

    /** Drop the hold, e.g. when there is no fresh reading. */
    public void clear(UUID userId) {
        held.remove(userId);
    }

    private static int priority(WatchStrip strip) {
        return switch (strip.state()) {
            case URGENT_LOW -> 2;
            case URGENT_HIGH -> 1;
            case CALM -> 0;
        };
    }
}
