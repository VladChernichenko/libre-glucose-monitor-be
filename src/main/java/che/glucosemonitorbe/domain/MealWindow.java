package che.glucosemonitorbe.domain;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Meal-window classification for circadian ISF analysis and per-window manual overrides.
 *
 * <p>Together the four windows partition the full day:
 * BREAKFAST 05:00-10:59, LUNCH 11:00-15:59, DINNER 16:00-21:59, NIGHT 22:00-04:59.</p>
 */
@Getter
public enum MealWindow {
    BREAKFAST(5, 11),   // 05:00-10:59
    LUNCH(11, 16),      // 11:00-15:59
    DINNER(16, 22),     // 16:00-21:59
    NIGHT(22, 5);       // 22:00-04:59 (wraps midnight)

    private final int startHourInclusive;
    private final int endHourExclusive;

    MealWindow(int startHourInclusive, int endHourExclusive) {
        this.startHourInclusive = startHourInclusive;
        this.endHourExclusive = endHourExclusive;
    }

    /** True when the window crosses midnight ({@link #NIGHT}). */
    public boolean wrapsMidnight() {
        return startHourInclusive > endHourExclusive;
    }

    /** Whether {@code hour} (0-23) falls in this window. */
    public boolean containsHour(int hour) {
        if (hour < 0 || hour > 23) {
            return false;
        }
        if (wrapsMidnight()) {
            return hour >= startHourInclusive || hour < endHourExclusive;
        }
        return hour >= startHourInclusive && hour < endHourExclusive;
    }

    /**
     * Classifies an hour-of-day into a meal window. Hours outside 0-23 return empty.
     */
    public static Optional<MealWindow> fromHour(int hour) {
        if (hour < 0 || hour > 23) {
            return Optional.empty();
        }
        for (MealWindow window : values()) {
            if (window.containsHour(hour)) {
                return Optional.of(window);
            }
        }
        return Optional.empty();
    }

    public static Optional<MealWindow> fromTimestamp(LocalDateTime ts) {
        return ts == null ? Optional.empty() : fromHour(ts.getHour());
    }
}
