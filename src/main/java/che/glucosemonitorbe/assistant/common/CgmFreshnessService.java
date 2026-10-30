package che.glucosemonitorbe.assistant.common;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a user's newest stored CGM reading may be presented as "current". A reading is
 * fresh when it is at most {@link #MAX_AGE} old and LibreLinkUp upstream is not reported down.
 * Users with no LibreLinkUp configured (e.g. Nightscout) have a CLOSED upstream breaker, so the
 * upstream check never blocks them.
 */
@Service
@RequiredArgsConstructor
public class CgmFreshnessService {

    public static final Duration MAX_AGE = Duration.ofMinutes(15);
    private static final double MGDL_PER_MMOL = 18.0;

    private final CgmReadingRepository readingRepository;
    private final LibreLinkUpSyncService libreLinkUpSyncService;

    /** {@code now} is UTC wall time (same convention as the other CGM window queries). */
    public Optional<CgmReadingDTO> freshReading(UUID userId, LocalDateTime now) {
        Optional<CgmReading> newest = readingRepository.findFirstByUserIdOrderByDateTimestampDesc(userId);
        if (newest.isEmpty() || newest.get().getDateTimestamp() == null || newest.get().getSgv() == null) {
            return Optional.empty();
        }
        CgmReading r = newest.get();
        Instant readAt = Instant.ofEpochMilli(r.getDateTimestamp());
        Duration age = Duration.between(readAt, now.toInstant(ZoneOffset.UTC));
        if (age.compareTo(MAX_AGE) > 0) {
            return Optional.empty();
        }
        if (!libreLinkUpSyncService.upstreamStatus(userId).available()) {
            return Optional.empty();
        }
        return Optional.of(new CgmReadingDTO(r.getSgv() / MGDL_PER_MMOL,
                LocalDateTime.ofInstant(readAt, ZoneOffset.UTC), r.getTrend(), r.getDirection()));
    }
}
