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
import java.util.OptionalDouble;
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
    /** How far back from the newest reading the trend's other reading is looked for. */
    static final Duration TREND_WINDOW = Duration.ofMinutes(15);
    /** How far from the {@link #TREND_WINDOW} mark that other reading may be. */
    static final Duration TREND_TOLERANCE = Duration.ofMinutes(5);
    private static final double MGDL_PER_MMOL = 18.0;

    private final CgmReadingRepository readingRepository;
    private final LibreLinkUpSyncService libreLinkUpSyncService;

    /** {@code now} is an absolute instant; the reading's epoch-ms is compared to it directly, so the JVM zone is irrelevant. */
    public Optional<CgmReadingDTO> freshReading(UUID userId, Instant now) {
        return freshNewest(userId, now).map(r -> new CgmReadingDTO(r.getSgv() / MGDL_PER_MMOL,
                LocalDateTime.ofInstant(Instant.ofEpochMilli(r.getDateTimestamp()), ZoneOffset.UTC),
                r.getTrend(), r.getDirection()));
    }

    /**
     * The current rate of change of the user's CGM [mmol/L per minute], from stored readings.
     *
     * <p>The newest reading must be fresh, exactly as {@link #freshReading} defines it. The other
     * end of the slope is the stored reading nearest to {@link #TREND_WINDOW} before the newest one,
     * no further than {@link #TREND_TOLERANCE} from that mark; rows without a value or a timestamp
     * are ignored. The trend is the glucose difference divided by the real time between the two
     * readings, taken from their epoch-ms timestamps, so the JVM zone is irrelevant.</p>
     *
     * <p>Empty when either reading is missing. The value is not clamped here:
     * {@code InterstitialLagModel.plasmaFromSensor} limits the trend it uses.</p>
     */
    public OptionalDouble freshTrendMmolPerMin(UUID userId, Instant now) {
        Optional<CgmReading> fresh = freshNewest(userId, now);
        if (fresh.isEmpty()) {
            return OptionalDouble.empty();
        }
        CgmReading newest = fresh.get();
        long targetMs = newest.getDateTimestamp() - TREND_WINDOW.toMillis();
        long toleranceMs = TREND_TOLERANCE.toMillis();
        CgmReading earlier = null;
        for (CgmReading r : readingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                userId, targetMs - toleranceMs, targetMs + toleranceMs)) {
            if (r.getSgv() == null || r.getDateTimestamp() == null) {
                continue;
            }
            if (earlier == null
                    || Math.abs(r.getDateTimestamp() - targetMs) < Math.abs(earlier.getDateTimestamp() - targetMs)) {
                earlier = r;
            }
        }
        if (earlier == null) {
            return OptionalDouble.empty();
        }
        // The window keeps the two readings 10 to 20 minutes apart, so the divisor is never 0.
        double minutes = (newest.getDateTimestamp() - earlier.getDateTimestamp()) / 60_000.0;
        return OptionalDouble.of((newest.getSgv() - earlier.getSgv()) / MGDL_PER_MMOL / minutes);
    }

    /** The newest stored reading when it has a value and a timestamp and is fresh; the raw row. */
    private Optional<CgmReading> freshNewest(UUID userId, Instant now) {
        Optional<CgmReading> newest = readingRepository.findFirstByUserIdOrderByDateTimestampDesc(userId);
        if (newest.isEmpty() || newest.get().getDateTimestamp() == null || newest.get().getSgv() == null) {
            return Optional.empty();
        }
        Duration age = Duration.between(Instant.ofEpochMilli(newest.get().getDateTimestamp()), now);
        if (age.compareTo(MAX_AGE) > 0) {
            return Optional.empty();
        }
        if (!libreLinkUpSyncService.upstreamStatus(userId).available()) {
            return Optional.empty();
        }
        return newest;
    }
}
