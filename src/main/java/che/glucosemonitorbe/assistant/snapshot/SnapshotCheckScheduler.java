package che.glucosemonitorbe.assistant.snapshot;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.empirical.EmpiricalTwinEngine;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Scores frozen forecast snapshots against the CGM at +60/+120/+240 min from {@code madeAt}.
 * A check with no reading within +-5 min of its minute stays null (retried each run until the
 * snapshot completes). A snapshot is complete when +240 is filled or 6 h have elapsed.
 */
@Slf4j
@Component
public class SnapshotCheckScheduler {

    static final Duration TOLERANCE = Duration.ofMinutes(5);
    static final Duration GIVE_UP_AFTER = Duration.ofHours(6);
    private static final double MGDL_PER_MMOL = 18.0;

    private final ForecastSnapshotRepository snapshots;
    private final CgmReadingRepository readings;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SnapshotCheckScheduler(ForecastSnapshotRepository snapshots, CgmReadingRepository readings,
                                  ObjectMapper objectMapper) {
        this(snapshots, readings, objectMapper, Clock.systemUTC());
    }

    SnapshotCheckScheduler(ForecastSnapshotRepository snapshots, CgmReadingRepository readings,
                           ObjectMapper objectMapper, Clock clock) {
        this.snapshots = snapshots;
        this.readings = readings;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 15 * 60 * 1000)
    public void checkSnapshots() {
        Instant now = clock.instant();
        for (ForecastSnapshot s : snapshots.findByCompleteFalseAndExcludedFalse()) {
            try {
                if (check(s, now)) {
                    snapshots.save(s);
                }
            } catch (Exception e) {
                log.warn("Snapshot {} check failed: {}", s.getId(), e.getMessage());
            }
        }
    }

    /** @return true when the snapshot changed. */
    private boolean check(ForecastSnapshot s, Instant now) throws Exception {
        boolean changed = false;
        JsonNode points = objectMapper.readTree(s.getPoints());
        if (s.getCheck60Hit() == null) {
            changed |= fill(s, points, 60, now, (x, a, h) -> { x.setCheck60Actual(a); x.setCheck60Hit(h); });
        }
        if (s.getCheck120Hit() == null) {
            changed |= fill(s, points, 120, now, (x, a, h) -> { x.setCheck120Actual(a); x.setCheck120Hit(h); });
        }
        if (s.getCheck240Hit() == null) {
            changed |= fill(s, points, 240, now, (x, a, h) -> { x.setCheck240Actual(a); x.setCheck240Hit(h); });
        }
        if (!s.isComplete() && (s.getCheck240Hit() != null || !now.isBefore(s.getMadeAt().plus(GIVE_UP_AFTER)))) {
            s.setComplete(true);
            changed = true;
        }
        return changed;
    }

    private interface Setter { void set(ForecastSnapshot s, Double actual, Boolean hit); }

    private boolean fill(ForecastSnapshot s, JsonNode points, int minute, Instant now, Setter setter) {
        Instant target = s.getMadeAt().plus(Duration.ofMinutes(minute));
        // Wait until the whole +-5 min window has passed so the nearest reading is final.
        if (now.isBefore(target.plus(TOLERANCE))) return false;
        Double predicted = predictedAt(points, minute);
        if (predicted == null) return false;
        Optional<Double> actual = actualNear(s, target);
        if (actual.isEmpty()) return false;
        setter.set(s, actual.get(), EmpiricalTwinEngine.isHit(predicted, actual.get()));
        return true;
    }

    private static Double predictedAt(JsonNode points, int minute) {
        for (JsonNode p : points) {
            if (p.path("minute").asInt(-1) == minute && p.hasNonNull("mmol")) {
                return p.get("mmol").asDouble();
            }
        }
        return null;
    }

    private Optional<Double> actualNear(ForecastSnapshot s, Instant target) {
        long lo = target.minus(TOLERANCE).toEpochMilli();
        long hi = target.plus(TOLERANCE).toEpochMilli();
        List<CgmReading> found = readings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                s.getUserId(), lo, hi);
        return found.stream()
                .filter(r -> r.getDateTimestamp() != null && r.getSgv() != null)
                .min(Comparator.comparingLong(r -> Math.abs(r.getDateTimestamp() - target.toEpochMilli())))
                .map(r -> r.getSgv() / MGDL_PER_MMOL);
    }
}
