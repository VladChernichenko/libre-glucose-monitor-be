package che.glucosemonitorbe.assistant.advice;

import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.service.observer.HypoThresholds;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * When the dashboard advises fast carbs, as a pure function of the forecast and the CGM trend: the
 * forecast minimum within 60 min is under {@link HypoThresholds#HYPO_MMOL}, or the trend is at or
 * below {@link #FALL_RATE} and the minimum within 90 min is under the same line. Shared by
 * {@link CarbAdviceService} and the meal replay harness, so the harness scores the live rule.
 */
public final class CarbAdviceTrigger {

    /** A CGM trend at or below this [mmol/L per min] is a fast fall. */
    public static final double FALL_RATE = -0.1;

    private CarbAdviceTrigger() {}

    /**
     * In meal context a forecast-only low needs the CGM to confirm it: a reading at or below this
     * [mmol/L], or a trend at or below {@link #FALL_RATE}.
     *
     * <p>5.5, not the spec's 4.5: tuned on AZT1D (last 14 days of 22 subjects, Gamma absorption) and
     * one user's notes before 2026-11-01 ({@code CarbAdviceGuardTuningTest}). At 4.5 the guard cut
     * meal-anchor false advice by 66% but left 3 real lows without advice and pushed 19 inside 15
     * min; at 5.0, -50% with 3 lows inside 15 min; at 5.5, -38% with no low losing its advice or its
     * lead. Chosen for safety: a real low always keeps its warning. A forecast low at a reading of
     * 5.0-5.5 after a meal (as on 2026-11-19 at 5.4) is still advised.</p>
     */
    public static final double CONFIRM_READING_MMOL = 5.5;

    /**
     * The guarded rule. Outside meal context it is {@link #fires(LocalDateTime, double, List)}. In
     * meal context ({@link MealContext}) the hypo-within-60 path also needs a CGM-confirmed fall
     * ({@code trend <= FALL_RATE} or {@code currentGlucose <= CONFIRM_READING_MMOL}, both inclusive):
     * after a meal the forecast's timing is least reliable and can show a low the sensor never sees.
     * The fast-fall path is its own confirmation and is unchanged.
     */
    public static boolean fires(LocalDateTime now, double currentGlucose, double trendMmolPerMin,
                                List<PredictionPointDTO> forecast, boolean mealContext) {
        if (!mealContext) return fires(now, trendMmolPerMin, forecast);
        boolean confirmed = trendMmolPerMin <= FALL_RATE || currentGlucose <= CONFIRM_READING_MMOL;
        boolean hypoWithin60 = confirmed && minimum(forecast, now, 60)
                .map(p -> p.getPredictedGlucose() < HypoThresholds.HYPO_MMOL).orElse(false);
        return hypoWithin60 || fastFall(now, trendMmolPerMin, forecast);
    }

    /** Whether the advice fires for {@code forecast} made at {@code now} with CGM trend {@code trendMmolPerMin}. */
    public static boolean fires(LocalDateTime now, double trendMmolPerMin, List<PredictionPointDTO> forecast) {
        boolean hypoWithin60 = minimum(forecast, now, 60)
                .map(p -> p.getPredictedGlucose() < HypoThresholds.HYPO_MMOL).orElse(false);
        return hypoWithin60 || fastFall(now, trendMmolPerMin, forecast);
    }

    private static boolean fastFall(LocalDateTime now, double trendMmolPerMin, List<PredictionPointDTO> forecast) {
        return trendMmolPerMin <= FALL_RATE && minimum(forecast, now, 90)
                .map(p -> p.getPredictedGlucose() < HypoThresholds.HYPO_MMOL).orElse(false);
    }

    /** The lowest forecast point in (now, now + minutes]. */
    public static Optional<PredictionPointDTO> minimum(List<PredictionPointDTO> forecast,
                                                       LocalDateTime now, int minutes) {
        if (forecast == null) return Optional.empty();
        LocalDateTime until = now.plusMinutes(minutes);
        return forecast.stream()
                .filter(p -> p.getTimestamp() != null && p.getPredictedGlucose() != null)
                .filter(p -> p.getTimestamp().isAfter(now) && !p.getTimestamp().isAfter(until))
                .min(Comparator.comparingDouble(PredictionPointDTO::getPredictedGlucose));
    }
}
