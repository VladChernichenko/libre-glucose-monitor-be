package che.glucosemonitorbe.service.observer;

import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure evaluation logic - no I/O, no Spring state.
 * Given a freshly-computed {@link GlucoseCalculationsResponse} plus contextual
 * signals, returns zero or more {@link GlucoseAlert}s that should be dispatched.
 *
 * <p>All thresholds are intentionally conservative so alerts fire <em>before</em>
 * the event, giving the user time to act.
 */
@Component
public class GlucoseAlertEvaluator {

    // -- Thresholds ------------------------------------------------------------

    /** mmol/L - glucose below this triggers hypo alerts. */
    private static final double HYPO_THRESHOLD       = 3.9;

    /** mmol/L - early warning before hard hypo floor. */
    private static final double HYPO_WARN_THRESHOLD  = 4.5;

    /** mmol/L - nadir below this after over-injection. */
    private static final double OVER_INJ_THRESHOLD   = 4.0;

    /** mmol/L - hyper alert ceiling. */
    private static final double HYPER_THRESHOLD      = 12.0;

    /** Units - IOB considered "negligible" (no hyper risk). */
    private static final double IOB_LOW              = 0.5;

    /** mmol/L/min - ROC threshold for "rising fast". */
    private static final double ROC_RISE             = 0.10;

    /** mmol/L/min - ROC threshold for "falling fast". */
    private static final double ROC_DROP             = -0.07;

    /**
     * Net glucose effect (carbEffect − insulinEffect) below this means
     * more insulin than carbs can cover -> over-injection risk.
     */
    private static final double OVER_INJ_NET_EFFECT  = -2.0;

    /** mmol/L - stability band; swings within ±2 are considered normal. */
    public static final double STABILITY_BAND        = 2.0;

    // -- Public API ------------------------------------------------------------

    /**
     * Evaluate a full calculation response for all alert types.
     * Called by the {@link GlucoseAnomalyDetector} on each CGM cycle.
     *
     * @param userId      the user being evaluated
     * @param calc        freshly-computed calculation response
     * @param roc         rate-of-change in mmol/L/min (negative = falling)
     * @param minutesSinceLastMealNote minutes since the most recent carb note (null = no note)
     * @param now         evaluation timestamp
     */
    public List<GlucoseAlert> evaluateAll(
            UUID userId,
            GlucoseCalculationsResponse calc,
            double roc,
            Integer minutesSinceLastMealNote,
            LocalDateTime now) {

        List<GlucoseAlert> alerts = new ArrayList<>();
        double current = calc.getCurrentGlucose() != null ? calc.getCurrentGlucose() : 0;
        List<PredictionPointDTO> path = calc.getPredictionPath();

        // P1 - Predicted hypo: any path point < 3.9 within 60 min
        findNadir(path, now, 60).ifPresent(nadir -> {
            if (nadir.glucose() < HYPO_THRESHOLD) {
                alerts.add(new GlucoseAlert(
                        userId, GlucoseAlert.Type.PREDICTED_HYPO,
                        current, nadir.glucose(),
                        nadir.time(), nadir.minutesAway(),
                        String.format("Glucose may drop to %.1f mmol/L in ~%d min",
                                nadir.glucose(), nadir.minutesAway()),
                        "Take fast-acting carbs now to prevent a low"
                ));
            }
        });

        // P2 - Rapid drop: ROC below threshold (evaluated by caller across readings)
        if (roc < ROC_DROP) {
            int eta = estimateMinutesToThreshold(path, HYPO_WARN_THRESHOLD, now);
            double predictedAt30 = glucoseAtMinutes(path, 30, current);
            alerts.add(new GlucoseAlert(
                    userId, GlucoseAlert.Type.RAPID_DROP,
                    current, predictedAt30,
                    now.plusMinutes(30), 30,
                    String.format("Falling fast (%.2f mmol/min) - now %.1f mmol/L",
                            roc, current),
                    eta > 0
                            ? String.format("Could reach %.1f in ~%d min - consider a small snack", HYPO_WARN_THRESHOLD, eta)
                            : "Consider a small snack if trend continues"
            ));
        }

        // P3 - Unlogged meal: rising fast + COB=0 + no note in 45 min
        double cob = calc.getActiveCarbsOnBoard() != null ? calc.getActiveCarbsOnBoard() : 0;
        if (roc > ROC_RISE
                && cob < 1.0
                && (minutesSinceLastMealNote == null || minutesSinceLastMealNote > 45)) {
            alerts.add(new GlucoseAlert(
                    userId, GlucoseAlert.Type.UNLOGGED_MEAL,
                    current, null,
                    now, 0,
                    String.format("Glucose rising fast (%.2f mmol/min) with no active carbs", roc),
                    "Did you eat something? Log a meal note to update your forecast"
            ));
        }

        // P4 - Predicted hyper: path > 12 within 2h + low IOB
        double iob = calc.getActiveInsulinOnBoard() != null ? calc.getActiveInsulinOnBoard() : 0;
        if (iob < IOB_LOW && cob > 0) {
            findPeakAbove(path, HYPER_THRESHOLD, now, 120).ifPresent(peak -> {
                alerts.add(new GlucoseAlert(
                        userId, GlucoseAlert.Type.PREDICTED_HYPER,
                        current, peak.glucose(),
                        peak.time(), peak.minutesAway(),
                        String.format("On track for %.1f mmol/L around %s",
                                peak.glucose(), peak.time().toLocalTime().toString()),
                        "Consider a correction dose now"
                ));
            });
        }

        return alerts;
    }

    /**
     * Over-injection check - call this immediately after an insulin note is saved,
     * before the CGM cycle fires. Compares IOB glucose-lowering power against
     * available COB carb buffer. If the net effect puts the nadir below 4.0,
     * returns an alert.
     *
     * @param userId       the user
     * @param calc         calculation run right after note save (includes the new dose)
     * @param insulinUnits the units just injected
     * @param now          timestamp of injection
     */
    public Optional<GlucoseAlert> evaluateOverInjection(
            UUID userId,
            GlucoseCalculationsResponse calc,
            double insulinUnits,
            LocalDateTime now) {

        if (insulinUnits <= 0) return Optional.empty();

        double current = calc.getCurrentGlucose() != null ? calc.getCurrentGlucose() : 0;
        List<PredictionPointDTO> path = calc.getPredictionPath();

        // Find the projected nadir across the full path
        Optional<NadirPoint> nadir = findNadir(path, now, 480);
        if (nadir.isEmpty()) return Optional.empty();

        double projectedNadir = nadir.get().glucose();
        int minutesToNadir    = nadir.get().minutesAway();

        // Also check the net-effect shortcut for early detection before path diverges
        double cobEffect = (calc.getActiveCarbsOnBoard() != null ? calc.getActiveCarbsOnBoard() : 0) / 10.0 * 2.0;
        double iobEffect = (calc.getActiveInsulinOnBoard() != null ? calc.getActiveInsulinOnBoard() : 0) * 1.0;
        double netEffect = cobEffect - iobEffect;

        boolean nadirBelowThreshold  = projectedNadir < OVER_INJ_THRESHOLD;
        boolean netEffectNegative     = netEffect < OVER_INJ_NET_EFFECT;

        if (!nadirBelowThreshold && !netEffectNegative) return Optional.empty();

        // Calculate recommended rescue carbs: grams needed to bring nadir up to 5.0
        double targetNadir  = 5.0;
        double glucoseGap   = targetNadir - projectedNadir;
        int rescueCarbs     = (int) Math.ceil(Math.max(0, glucoseGap / 0.2)); // ~0.2 mmol/L per gram fast carbs

        String message = String.format(
                "Insulin dose (%.1fu) may exceed carb coverage - glucose could drop to %.1f mmol/L in ~%d min",
                insulinUnits, projectedNadir, minutesToNadir);

        String action = rescueCarbs > 0
                ? String.format("Consider eating %dg of fast carbs to prevent a low", rescueCarbs)
                : "Monitor closely - low expected";

        return Optional.of(new GlucoseAlert(
                userId, GlucoseAlert.Type.OVER_INJECTION,
                current, projectedNadir,
                nadir.get().time(), minutesToNadir,
                message, action
        ));
    }

    // -- Private helpers -------------------------------------------------------

    /**
     * Find the minimum predicted glucose within {@code horizonMinutes},
     * returning its value, time, and minutes-away.
     */
    private Optional<NadirPoint> findNadir(
            List<PredictionPointDTO> path, LocalDateTime now, int horizonMinutes) {
        if (path == null || path.isEmpty()) return Optional.empty();
        LocalDateTime cutoff = now.plusMinutes(horizonMinutes);
        return path.stream()
                .filter(p -> p.getTimestamp() != null
                        && p.getPredictedGlucose() != null
                        && !p.getTimestamp().isAfter(cutoff))
                .min((a, b) -> Double.compare(a.getPredictedGlucose(), b.getPredictedGlucose()))
                .map(p -> new NadirPoint(
                        p.getPredictedGlucose(),
                        p.getTimestamp(),
                        (int) ChronoUnit.MINUTES.between(now, p.getTimestamp())
                ));
    }

    /**
     * Find the first point above {@code threshold} within {@code horizonMinutes}.
     */
    private Optional<NadirPoint> findPeakAbove(
            List<PredictionPointDTO> path, double threshold, LocalDateTime now, int horizonMinutes) {
        if (path == null || path.isEmpty()) return Optional.empty();
        LocalDateTime cutoff = now.plusMinutes(horizonMinutes);
        return path.stream()
                .filter(p -> p.getTimestamp() != null
                        && p.getPredictedGlucose() != null
                        && !p.getTimestamp().isAfter(cutoff)
                        && p.getPredictedGlucose() > threshold)
                .findFirst()
                .map(p -> new NadirPoint(
                        p.getPredictedGlucose(),
                        p.getTimestamp(),
                        (int) ChronoUnit.MINUTES.between(now, p.getTimestamp())
                ));
    }

    /** Predicted glucose at exactly {@code minutes} from now (linear interpolation). */
    private double glucoseAtMinutes(
            List<PredictionPointDTO> path, int minutes, double fallback) {
        if (path == null || path.isEmpty()) return fallback;
        return path.stream()
                .filter(p -> p.getTimestamp() != null && p.getPredictedGlucose() != null)
                .min((a, b) -> {
                    // find the point closest to `minutes` from now
                    long da = Math.abs(ChronoUnit.MINUTES.between(
                            LocalDateTime.now(), a.getTimestamp()) - minutes);
                    long db = Math.abs(ChronoUnit.MINUTES.between(
                            LocalDateTime.now(), b.getTimestamp()) - minutes);
                    return Long.compare(da, db);
                })
                .map(PredictionPointDTO::getPredictedGlucose)
                .orElse(fallback);
    }

    /**
     * Estimate how many minutes until the prediction path first reaches
     * {@code threshold} from above (falling). Returns -1 if not reached.
     */
    private int estimateMinutesToThreshold(
            List<PredictionPointDTO> path, double threshold, LocalDateTime now) {
        if (path == null) return -1;
        return path.stream()
                .filter(p -> p.getTimestamp() != null
                        && p.getPredictedGlucose() != null
                        && p.getPredictedGlucose() <= threshold)
                .mapToInt(p -> (int) ChronoUnit.MINUTES.between(now, p.getTimestamp()))
                .filter(m -> m >= 0)
                .min()
                .orElse(-1);
    }

    /** Value-object returned by nadir / peak finders. */
    private record NadirPoint(double glucose, LocalDateTime time, int minutesAway) {}
}
