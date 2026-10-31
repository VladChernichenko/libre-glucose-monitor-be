package che.glucosemonitorbe.assistant.snapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Rolling forecast hit rate over the last 14 days. Only the +60 and +120 checks count: +240 is
 * dominated by unlogged food/insulin and is reported per snapshot, not in the headline number.
 */
@Service
@RequiredArgsConstructor
public class HitRateService {

    static final Duration WINDOW = Duration.ofDays(14);

    private final ForecastSnapshotRepository repository;

    /** {@code percent} is null when no check has been scored yet. */
    public record HitRate(int hits, int checks, Double percent) {}

    public HitRate hitRate(UUID userId, Instant now) {
        int hits = 0;
        int checks = 0;
        for (ForecastSnapshot s : repository.findByUserIdAndExcludedFalseAndMadeAtGreaterThanEqual(
                userId, now.minus(WINDOW))) {
            if (s.isExcluded()) continue;
            for (Boolean hit : new Boolean[] {s.getCheck60Hit(), s.getCheck120Hit()}) {
                if (hit == null) continue;
                checks++;
                if (hit) hits++;
            }
        }
        return new HitRate(hits, checks, checks == 0 ? null : 100.0 * hits / checks);
    }
}
