package che.glucosemonitorbe.assistant.common;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.LibreUpstreamStatusDTO;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CgmFreshnessServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);

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
        long ts = NOW.minusMinutes(minutes).toInstant(ZoneOffset.UTC).toEpochMilli();
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
        assertThat(r.get().timestamp()).isEqualTo(NOW.minusMinutes(14));
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
}
