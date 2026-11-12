package che.glucosemonitorbe.assistant.common;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.LibreUpstreamStatusDTO;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CgmFreshnessServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private final CgmReadingRepository readings = mock(CgmReadingRepository.class);
    private final LibreLinkUpSyncService libre = mock(LibreLinkUpSyncService.class);
    private final UUID userId = UUID.randomUUID();
    private CgmFreshnessService service;

    @BeforeEach
    void setUp() {
        service = new CgmFreshnessService(readings, libre);
        when(libre.upstreamStatus(userId)).thenReturn(new LibreUpstreamStatusDTO(true, "CLOSED", null));
    }

    private void newestReadingAgeMinutes(int minutes) {
        long ts = NOW.minus(Duration.ofMinutes(minutes)).toEpochMilli();
        when(readings.findFirstByUserIdOrderByDateTimestampDesc(userId)).thenReturn(Optional.of(
                CgmReading.builder().userId(userId).sgv(126).dateTimestamp(ts).trend(4).direction("Flat").build()));
    }

    @Test
    @DisplayName("a 14-minute-old reading is fresh")
    void freshWhenReadingIs14MinOld() {
        newestReadingAgeMinutes(14);
        Optional<CgmReadingDTO> r = service.freshReading(userId, NOW);
        assertThat(r).isPresent();
        assertThat(r.get().mmol()).isEqualTo(7.0);
        assertThat(r.get().timestamp()).isEqualTo(LocalDateTime.ofInstant(NOW.minus(Duration.ofMinutes(14)), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a 16-minute-old reading is stale")
    void staleWhenReadingIs16MinOld() {
        newestReadingAgeMinutes(16);
        assertThat(service.freshReading(userId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("a recent reading is stale when LibreLinkUp upstream is down")
    void staleWhenUpstreamDown() {
        newestReadingAgeMinutes(2);
        when(libre.upstreamStatus(userId)).thenReturn(new LibreUpstreamStatusDTO(false, "OPEN", 120L));
        assertThat(service.freshReading(userId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("no readings at all is stale")
    void staleWhenNoReadings() {
        when(readings.findFirstByUserIdOrderByDateTimestampDesc(userId)).thenReturn(Optional.empty());
        assertThat(service.freshReading(userId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("freshness does not depend on the JVM default time zone")
    void freshnessIsIndependentOfJvmZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"Asia/Dubai", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                newestReadingAgeMinutes(14);
                assertThat(service.freshReading(userId, NOW)).as("14 min in " + zone).isPresent();
                newestReadingAgeMinutes(16);
                assertThat(service.freshReading(userId, NOW)).as("16 min in " + zone).isEmpty();
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("the trend is the slope from the reading about 15 minutes before the newest one")
    void trendFromTheReadingFifteenMinutesEarlier() {
        long newest = NOW.minus(Duration.ofMinutes(2)).toEpochMilli();
        long earlier = newest - Duration.ofMinutes(14).toMillis();
        when(readings.findFirstByUserIdOrderByDateTimestampDesc(userId)).thenReturn(Optional.of(
                CgmReading.builder().userId(userId).sgv(151).dateTimestamp(newest).build()));
        long target = newest - Duration.ofMinutes(15).toMillis();
        when(readings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                userId, target - 300_000L, target + 300_000L))
                .thenReturn(List.of(
                        CgmReading.builder().userId(userId).sgv(90).dateTimestamp(target - 280_000L).build(),
                        CgmReading.builder().userId(userId).sgv(126).dateTimestamp(earlier).build()));

        // 151 - 126 = 25 mg/dL over 14 min, from the reading nearest the 15-minute mark
        assertThat(service.freshTrendMmolPerMin(userId, NOW).getAsDouble())
                .isCloseTo(25.0 / 18.0 / 14.0, within(1e-9));
    }

    /** A valid stored reading 15 minutes before the newest one, whatever window is asked for. */
    private void earlierReadingExists() {
        long newest = readings.findFirstByUserIdOrderByDateTimestampDesc(userId).orElseThrow().getDateTimestamp();
        when(readings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(eq(userId), anyLong(), anyLong()))
                .thenReturn(List.of(CgmReading.builder().userId(userId).sgv(108)
                        .dateTimestamp(newest - Duration.ofMinutes(15).toMillis()).build()));
    }

    @Test
    @DisplayName("no trend from a stale newest reading, even with an earlier one to pair it with")
    void noTrendFromAStaleReading() {
        newestReadingAgeMinutes(16);
        earlierReadingExists();
        assertThat(service.freshTrendMmolPerMin(userId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("no trend while upstream is down, even with a fresh pair of readings")
    void noTrendWhileUpstreamIsDown() {
        newestReadingAgeMinutes(2);
        earlierReadingExists();
        when(libre.upstreamStatus(userId)).thenReturn(new LibreUpstreamStatusDTO(false, "OPEN", 120L));
        assertThat(service.freshTrendMmolPerMin(userId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("no trend without an earlier reading")
    void noTrendWithoutAnEarlierReading() {
        newestReadingAgeMinutes(2);
        assertThat(service.freshTrendMmolPerMin(userId, NOW)).isEmpty();
    }
}
