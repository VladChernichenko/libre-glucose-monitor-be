package che.glucosemonitorbe.service.observer;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates alert evaluation and dispatch.
 *
 * <p>All public methods are {@code @Async} - they run on a background thread so
 * neither note-save nor the CGM sync cycle is blocked.
 *
 * <p>Push delivery is a stub ({@link #deliverAlert}) until Phase 2 APNs
 * infrastructure is wired in. The stub logs at WARN level so alerts are
 * visible in server logs from day one.
 *
 * <p>Cooldown state is kept in-memory. On restart the cooldown resets, which
 * is acceptable - a missed alert after restart is far less harmful than a
 * missed hypo. Replace with {@code AlertSuppression} DB table (Phase 2).
 */
@Service
@RequiredArgsConstructor
public class GlucoseAlertService {

    private static final Logger log = LoggerFactory.getLogger(GlucoseAlertService.class);

    private final GlucoseAlertEvaluator evaluator;
    private final GlucoseCalculationsService calculationsService;
    private final FeatureToggleConfig featureToggleConfig;

    /**
     * Cooldown map: key = "userId:alertType", value = last-sent timestamp.
     * Replaced by DB table in Phase 2.
     */
    private final Map<String, LocalDateTime> cooldownMap = new ConcurrentHashMap<>();

    // -- Over-injection check (fired at note-save) -----------------------------

    /**
     * Run immediately after an insulin note is saved.
     * Computes a fresh prediction (which now includes the new dose) and checks
     * whether IOB will exceed COB buffer, projecting a nadir below 4.0 mmol/L.
     *
     * @param userId       user UUID
     * @param username     Spring Security principal (needed by calculations service)
     * @param insulinUnits units just injected
     * @param currentGlucose glucose at time of injection (mmol/L); null = skip
     */
    @Async
    public void checkOverInjection(UUID userId, String username,
                                   double insulinUnits, Double currentGlucose) {
        if (!featureToggleConfig.isGlucoseCalculationsEnabled()) return;
        if (currentGlucose == null || currentGlucose <= 0) return;

        try {
            LocalDateTime now = LocalDateTime.now();
            GlucoseCalculationsResponse calc = runCalculations(username, currentGlucose, now);
            if (calc == null) return;

            Optional<GlucoseAlert> alert =
                    evaluator.evaluateOverInjection(userId, calc, insulinUnits, now);
            alert.ifPresent(this::maybeDispatch);

        } catch (Exception e) {
            log.warn("Over-injection check failed for user {}: {}", userId, e.getMessage());
        }
    }

    // -- Full evaluation (fired by GlucoseAnomalyDetector on CGM cycle) --------

    /**
     * Evaluate all alert types against the latest CGM data for one user.
     *
     * @param userId                    user UUID
     * @param username                  Spring Security principal
     * @param currentGlucose            latest CGM reading (mmol/L)
     * @param roc                       rate of change mmol/L/min (negative = falling)
     * @param minutesSinceLastMealNote  minutes since last note with carbs > 0 (null if none)
     */
    @Async
    public void evaluateAll(UUID userId, String username,
                            double currentGlucose, double roc,
                            Integer minutesSinceLastMealNote) {
        if (!featureToggleConfig.isGlucoseCalculationsEnabled()) return;

        try {
            LocalDateTime now = LocalDateTime.now();
            GlucoseCalculationsResponse calc = runCalculations(username, currentGlucose, now);
            if (calc == null) return;

            List<GlucoseAlert> alerts =
                    evaluator.evaluateAll(userId, calc, roc, minutesSinceLastMealNote, now);
            alerts.forEach(this::maybeDispatch);

        } catch (Exception e) {
            log.warn("Alert evaluation failed for user {}: {}", userId, e.getMessage());
        }
    }

    // -- Dispatch --------------------------------------------------------------

    private void maybeDispatch(GlucoseAlert alert) {
        String key = alert.userId() + ":" + alert.type().name();
        LocalDateTime lastSent = cooldownMap.get(key);
        if (lastSent != null &&
                lastSent.plusMinutes(alert.cooldownMinutes()).isAfter(LocalDateTime.now())) {
            log.debug("Alert suppressed (cooldown): {} for user {}", alert.type(), alert.userId());
            return;
        }
        cooldownMap.put(key, LocalDateTime.now());
        deliverAlert(alert);
    }

    /**
     * Stub delivery - logs the alert.
     * Replace with APNs / Firebase call in Phase 2.
     *
     * Format kept machine-parseable so log-aggregators (Datadog, Loki) can
     * surface alerts before push is wired.
     */
    private void deliverAlert(GlucoseAlert alert) {
        log.warn("[GLUCOSE_ALERT] type={} critical={} user={} current={}mmol projected={}mmol " +
                        "etaMin={} message=\"{}\" action=\"{}\"",
                alert.type().name(),
                alert.isCritical(),
                alert.userId(),
                String.format("%.1f", alert.currentGlucose()),
                alert.projectedGlucose() != null
                        ? String.format("%.1f", alert.projectedGlucose()) : "n/a",
                alert.minutesUntilEvent(),
                alert.message(),
                alert.actionSuggestion()
        );

        // TODO Phase 2: pushNotificationService.send(alert)
        // The push service will look up the user's APNs device token and send
        // an APNs payload with interruption-level=critical for PREDICTED_HYPO / OVER_INJECTION.
    }

    // -- Calculation helper ----------------------------------------------------

    private GlucoseCalculationsResponse runCalculations(
            String username, double currentGlucose, LocalDateTime now) {
        try {
            GlucoseCalculationsRequest req = GlucoseCalculationsRequest.builder()
                    .currentGlucose(currentGlucose)
                    .userId(username)
                    .includePredictionFactors(true)
                    .clientTimeInfo(ClientTimeInfo.builder()
                            .timestamp(now.toString())
                            .timezone(TimeZone.getDefault().getID())
                            .locale("en-US")
                            .timezoneOffset(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
                            .build())
                    .build();
            return calculationsService.calculateGlucoseData(req);
        } catch (Exception e) {
            log.debug("Calculation failed in alert service: {}", e.getMessage());
            return null;
        }
    }
}
