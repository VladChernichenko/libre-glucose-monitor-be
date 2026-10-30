package che.glucosemonitorbe.assistant.advice;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No-flicker hold for {@link CarbAdvice} (spec §6.13): once shown, the advice stays until two
 * consecutive NEW stored CGM readings no longer meet the trigger. Counted per stored reading time,
 * not per API call, so the dashboard polling the same reading twice never counts twice.
 *
 * <p>In memory, per user: a restart drops the hold, which at worst lets the advice go one reading
 * early or reappear on the next triggering reading.
 */
@Component
public class CarbAdviceHold {

    /** Consecutive new non-triggering readings that end the hold. */
    static final int CLEARING_READINGS = 2;

    private record Held(CarbAdvice advice, LocalDateTime readingTime, int clearStreak) {}

    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    /** The trigger is met on the reading stored at {@code readingTime}: show and hold {@code advice}. */
    public CarbAdvice triggered(UUID userId, LocalDateTime readingTime, CarbAdvice advice) {
        held.put(userId, new Held(advice, readingTime, 0));
        return advice;
    }

    /**
     * The trigger is not met on the reading stored at {@code readingTime}. Returns the held advice
     * while the hold lasts, else empty.
     */
    public Optional<CarbAdvice> notTriggered(UUID userId, LocalDateTime readingTime) {
        Held after = held.computeIfPresent(userId, (id, h) -> {
            boolean newReading = readingTime != null
                    && (h.readingTime() == null || readingTime.isAfter(h.readingTime()));
            if (!newReading) return h;
            int streak = h.clearStreak() + 1;
            return streak >= CLEARING_READINGS ? null : new Held(h.advice(), readingTime, streak);
        });
        return after == null ? Optional.empty() : Optional.of(after.advice());
    }

    /** Drop the hold, e.g. when the low flow takes over. */
    public void clear(UUID userId) {
        held.remove(userId);
    }
}
