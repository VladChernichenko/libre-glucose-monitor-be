package che.glucosemonitorbe.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AwakeTimeTest {

    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(7, 0);

    private static Duration awake(String from, String to, ZoneId zone) {
        return AwakeTime.between(Instant.parse(from), Instant.parse(to), zone, NIGHT_START, NIGHT_END);
    }

    @Test
    void daytimeOnly_countsWallTime() {
        assertThat(awake("2026-11-26T08:00:00Z", "2026-11-26T18:00:00Z", ZoneOffset.UTC))
                .isEqualTo(Duration.ofHours(10));
    }

    @Test
    void nightIsExcluded() {
        // 20:00 -> next 12:00: 2 h before 22:00 + 5 h after 07:00.
        assertThat(awake("2026-11-26T20:00:00Z", "2026-11-27T12:00:00Z", ZoneOffset.UTC))
                .isEqualTo(Duration.ofHours(7));
    }

    @Test
    void lastUseAtNight_tenDaytimeHoursEndAt17() {
        assertThat(awake("2026-11-26T23:30:00Z", "2026-11-27T17:00:00Z", ZoneOffset.UTC))
                .isEqualTo(Duration.ofHours(10));
    }

    @Test
    void usesUserLocalClock() {
        // 18:00Z..04:00Z is 22:00..08:00 in UTC+4: only 07:00-08:00 local is daytime.
        ZoneId plus4 = ZoneOffset.ofHours(4);
        assertThat(awake("2026-11-26T18:00:00Z", "2026-11-27T04:00:00Z", plus4))
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    void nonWrappingNight() {
        Duration d = AwakeTime.between(Instant.parse("2026-11-26T22:00:00Z"), Instant.parse("2026-11-27T08:00:00Z"),
                ZoneOffset.UTC, LocalTime.MIDNIGHT, LocalTime.of(6, 0));
        assertThat(d).isEqualTo(Duration.ofHours(4)); // 22-24 + 06-08
    }

    @Test
    void equalBounds_meanNoNight() {
        Duration d = AwakeTime.between(Instant.parse("2026-11-26T22:00:00Z"), Instant.parse("2026-11-27T08:00:00Z"),
                ZoneOffset.UTC, LocalTime.NOON, LocalTime.NOON);
        assertThat(d).isEqualTo(Duration.ofHours(10));
    }

    @Test
    void emptyOrReversedRange_isZero() {
        assertThat(awake("2026-11-26T12:00:00Z", "2026-11-26T12:00:00Z", ZoneOffset.UTC)).isZero();
        assertThat(awake("2026-11-26T13:00:00Z", "2026-11-26T12:00:00Z", ZoneOffset.UTC)).isZero();
    }

    @Test
    void dstSpringForwardDay_countsRealHours() {
        // Europe/Berlin 2026-03-29: 02:00 -> 03:00. 07:00 local (05:00Z) to 21:00 local (19:00Z) = 14 h.
        assertThat(awake("2026-03-29T05:00:00Z", "2026-03-29T19:00:00Z", ZoneId.of("Europe/Berlin")))
                .isEqualTo(Duration.ofHours(14));
    }
}
