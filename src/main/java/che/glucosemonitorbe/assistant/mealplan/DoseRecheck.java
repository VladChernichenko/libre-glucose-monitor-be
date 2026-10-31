package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.service.observer.HypoThresholds;

import java.util.List;

/**
 * The low re-check of a dose the user is about to choose on a meal card (spec 6.6): the dashboard
 * forecast for that dose with the plan's meal and pause, its lowest point and a level.
 *
 * @param curve       the forecast (empty when refused)
 * @param min         lowest forecast point [mmol/L] (null when refused)
 * @param level       {@link #RED} when {@code min} is under 3.9 (the client requires a second tap),
 *                    {@link #AMBER} when under 4.5, else {@link #OK}; null when refused
 * @param refusalCode null, or why no forecast was run and the dose must not be used:
 *                    {@link #PLAN_EXPIRED}, {@link #CGM_STALE}, {@link #FORECAST_UNAVAILABLE}, or a
 *                    {@code DosingRefusalReason} name ({@code DOSE_EXCEEDS_MAX_BOLUS},
 *                    {@code GLUCOSE_BELOW_SAFE_THRESHOLD}, {@code SETTINGS_INVALID})
 */
public record DoseRecheck(List<PredictionPointDTO> curve, Double min, String level, String refusalCode) {

    public static final String OK = "OK";
    public static final String AMBER = "AMBER";
    public static final String RED = "RED";

    /** The re-check came after the plan's {@code doseValidUntil}: re-plan. */
    public static final String PLAN_EXPIRED = "PLAN_EXPIRED";
    /** No fresh CGM reading (older than 15 min, or the upstream is down). */
    public static final String CGM_STALE = "CGM_STALE";
    /** The forecast returned no usable points. */
    public static final String FORECAST_UNAVAILABLE = "FORECAST_UNAVAILABLE";

    /** RED under 3.9, AMBER under 4.5 (both strict), else OK. */
    public static String level(double min) {
        if (min < HypoThresholds.HYPO_MMOL) return RED;
        if (min < HypoThresholds.RECOVERY_MMOL) return AMBER;
        return OK;
    }

    static DoseRecheck refused(String code) {
        return new DoseRecheck(List.of(), null, null, code);
    }
}
