package che.glucosemonitorbe.domain;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Meal-window classification for circadian ISF analysis.
 *
 * <p>A bolus's {@link LocalDateTime#getHour()} maps to one of the three windows below.
 * Night hours (22:00–04:59) intentionally map to {@code Optional.empty()} — corrections
 * during sleep are rare and signal-noisy, so we exclude them from the ISF profile.</p>
 */
@Getter
public enum MealWindow {
    BREAKFAST(5, 11),   // 05:00–10:59
    LUNCH(11, 16),      // 11:00–15:59
    DINNER(16, 22);     // 16:00–21:59

    private final int startHourInclusive;
    private final int endHourExclusive;

    MealWindow(int startHourInclusive, int endHourExclusive) {
        this.startHourInclusive = startHourInclusive;
        this.endHourExclusive = endHourExclusive;
    }

    /**
     * Classifies an hour-of-day into a meal window, or returns empty for night (22:00–04:59).
     */
    public static Optional<MealWindow> fromHour(int hour) {
        if (hour < 0 || hour > 23) {
            return Optional.empty();
        }
        for (MealWindow window : values()) {
            if (hour >= window.startHourInclusive && hour < window.endHourExclusive) {
                return Optional.of(window);
            }
        }
        return Optional.empty();
    }

    public static Optional<MealWindow> fromTimestamp(LocalDateTime ts) {
        return ts == null ? Optional.empty() : fromHour(ts.getHour());
    }
}
