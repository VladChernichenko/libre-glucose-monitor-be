package che.glucosemonitorbe.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Daytime ("awake") duration between two instants on a user's local clock, with a nightly window
 * excluded. The night may wrap midnight (22:00-07:00) or not (00:00-06:00); equal bounds mean no
 * night. Local times go through {@code atZone}, so DST days count their real length.
 */
public final class AwakeTime {

    private AwakeTime() {
    }

    public static Duration between(Instant from, Instant to, ZoneId zone, LocalTime nightStart, LocalTime nightEnd) {
        if (from == null || to == null || !to.isAfter(from)) {
            return Duration.ZERO;
        }
        if (nightStart.equals(nightEnd)) {
            return Duration.between(from, to);
        }
        LocalDate day = from.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = to.atZone(zone).toLocalDate();
        long ms = 0;
        for (; !day.isAfter(last); day = day.plusDays(1)) {
            if (nightStart.isAfter(nightEnd)) {
                // Night wraps midnight: awake is [nightEnd, nightStart) of this day.
                ms += overlapMs(at(day, nightEnd, zone), at(day, nightStart, zone), from, to);
            } else {
                // Night inside one day: awake is [00:00, nightStart) and [nightEnd, next 00:00).
                ms += overlapMs(day.atStartOfDay(zone).toInstant(), at(day, nightStart, zone), from, to);
                ms += overlapMs(at(day, nightEnd, zone), day.plusDays(1).atStartOfDay(zone).toInstant(), from, to);
            }
        }
        return Duration.ofMillis(ms);
    }

    private static Instant at(LocalDate day, LocalTime time, ZoneId zone) {
        return day.atTime(time).atZone(zone).toInstant();
    }

    private static long overlapMs(Instant a, Instant b, Instant from, Instant to) {
        Instant start = a.isAfter(from) ? a : from;
        Instant end = b.isBefore(to) ? b : to;
        return end.isAfter(start) ? Duration.between(start, end).toMillis() : 0;
    }
}
