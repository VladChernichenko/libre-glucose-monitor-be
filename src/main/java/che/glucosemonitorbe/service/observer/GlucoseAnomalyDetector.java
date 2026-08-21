package che.glucosemonitorbe.service.observer;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled observer that evaluates glucose curves for all active users
 * on every CGM sync cycle (every 5 minutes).
 *
 * <h3>Rate-of-change calculation</h3>
 * Uses CGM readings from the last {@value #ROC_WINDOW_MINUTES} minutes (read from
 * {@code cgm_readings}) to compute a least-squares slope in mmol/L per minute. Three points
 * are the minimum for noise rejection; LibreLinkUp delivers a new value every 5 min, so the
 * window is always fresh.
 *
 * <h3>Scenarios detected</h3>
 * <ul>
 *   <li>Predicted hypo - path point &lt; 3.9 within 60 min</li>
 *   <li>Rapid drop - ROC &lt; −0.07 for >= 2 consecutive readings</li>
 *   <li>Unlogged meal - ROC &gt; +0.10 &amp; COB=0 &amp; no note 45 min</li>
 *   <li>Predicted hyper - path &gt; 12 within 2h &amp; IOB &lt; 0.5u</li>
 * </ul>
 * Over-injection is NOT re-checked here - it fires immediately at note-save
 * via {@link GlucoseAlertService#checkOverInjection}.
 */
@Component
@RequiredArgsConstructor
public class GlucoseAnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(GlucoseAnomalyDetector.class);

    /** Minimum number of CGM readings needed to compute a reliable ROC. */
    private static final int MIN_READINGS_FOR_ROC = 2;

    /** Lookback window for CGM readings used in ROC computation (minutes). */
    private static final int ROC_WINDOW_MINUTES = 20;

    /** Lookback window for "did the user log a meal note?" check (minutes). */
    private static final int UNLOGGED_MEAL_WINDOW_MINUTES = 45;

    private final CgmReadingRepository cgmReadingRepository;
    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final GlucoseAlertService alertService;
    private final che.glucosemonitorbe.service.HypoEventService hypoEventService;

    /**
     * Runs every 5 minutes, aligned with the LibreLinkUp CGM sync cadence.
     * Each user is evaluated independently; failures are caught per-user so
     * one bad account does not block others.
     */
    @Scheduled(fixedDelayString = "${app.observer.interval-ms:300000}")
    public void scan() {
        log.debug("GlucoseAnomalyDetector scan started");
        try {
            userRepository.findAll().forEach(user -> {
                try {
                    evaluateUser(user.getId(), user.getUsername());
                } catch (Exception e) {
                    log.warn("Anomaly scan failed for user {}: {}", user.getId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("GlucoseAnomalyDetector scan aborted: {}", e.getMessage());
        }
    }

    // -- Per-user evaluation ---------------------------------------------------

    /** A CGM sample reduced to what the rate-of-change maths needs. */
    public record GlucosePoint(long epochMs, double mmol) {}

    void evaluateUser(UUID userId, String username) {
        LocalDateTime now = LocalDateTime.now();

        // 1. Collect recent CGM readings from the shared CGM cache. This used to read
        //    notes.glucose_value, which only ever holds client-supplied values from a logged
        //    note - neither sync scheduler writes it - so the observer never fired on real data.
        //
        //    Deliberately zone-free: this window is "the last N minutes of real elapsed time",
        //    not a wall-clock date range, so it is computed directly off epoch millis. Round-
        //    tripping through LocalDateTime.now().toInstant(ZoneOffset.UTC) would reinterpret
        //    JVM-local wall time as if it were UTC, shifting the window by the zone offset on
        //    any non-UTC deployment (this repo runs on Europe/London) and matching no rows.
        long endMs   = System.currentTimeMillis();
        long startMs = endMs - ROC_WINDOW_MINUTES * 60_000L;

        List<GlucosePoint> recentReadings = cgmReadingRepository
                .findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(userId, startMs, endMs)
                .stream()
                .filter(r -> r.getDateTimestamp() != null && r.getSgv() != null && r.getSgv() > 0)
                .map(r -> new GlucosePoint(r.getDateTimestamp(),
                                           GlucoseConversion.mgdlToMmol(r.getSgv())))
                .toList();

        if (recentReadings.size() < MIN_READINGS_FOR_ROC) {
            // Not enough data to compute a meaningful ROC - skip this cycle.
            return;
        }

        double currentGlucose = recentReadings.get(recentReadings.size() - 1).mmol();
        double roc = computeRoc(recentReadings);

        // 2. Find minutes since the last carb note. Still note-based - correctly so.
        LocalDateTime mealWindow = now.minusMinutes(UNLOGGED_MEAL_WINDOW_MINUTES);
        List<Note> recentNotes = noteRepository.findByUserIdAndTimestampBetween(userId, mealWindow, now);
        Integer minutesSinceLastMeal = recentNotes.stream()
                .filter(n -> n.getCarbs() != null && n.getCarbs() > 0)
                .max(Comparator.comparing(Note::getTimestamp))
                .map(n -> (int) ChronoUnit.MINUTES.between(n.getTimestamp(), now))
                .orElse(null);

        // 3. Advance the hypo-prompt lifecycle from the same reading the alerts use.
        hypoEventService.onGlucoseReading(userId, currentGlucose);

        // 4. Dispatch async evaluation (non-blocking)
        alertService.evaluateAll(userId, username, currentGlucose, roc, minutesSinceLastMeal);
    }

    // -- Rate-of-change computation --------------------------------------------

    /**
     * Ordinary least-squares slope over the provided readings.
     * Returns mmol/L per minute; negative = falling.
     *
     * <p>OLS is more robust than a simple first-last delta because a single
     * sensor glitch in the middle does not dominate the result.
     */
    static double computeRoc(List<GlucosePoint> readings) {
        if (readings.size() < 2) return 0.0;

        long t0 = readings.get(0).epochMs();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = readings.size();

        for (GlucosePoint r : readings) {
            double x = (r.epochMs() - t0) / 60_000.0;   // minutes since first reading
            double y = r.mmol();
            sumX  += x;
            sumY  += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denom = n * sumX2 - sumX * sumX;
        if (Math.abs(denom) < 1e-9) return 0.0;          // all readings at same timestamp
        return (n * sumXY - sumX * sumY) / denom;          // slope in mmol/L per minute
    }
}
