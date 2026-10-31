package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.dto.PredictionPointDTO;

import java.util.List;

/**
 * The pre-bolus pause chosen on the dashboard forecast and that forecast's curve.
 *
 * @param pauseMinutes minutes between the injection (now) and the first bite; null when a pre-bolus
 *                     is already in flight or every candidate's forecast minimum is under 4.5 mmol/L
 *                     ({@link #reason} says which). The curve is then the eat-now forecast.
 * @param curve        the dashboard forecast for this dose and pause
 * @param peak         highest point of {@code curve} [mmol/L]; null when the curve is empty
 * @param min          lowest point of {@code curve} [mmol/L]; null when the curve is empty
 * @param reason       one sentence on why this pause (or none)
 * @param inFlight     true only when a pre-bolus is already in flight: the curve then has the meal now
 *                     and only the calculator's top-up (if any) now, so it is not comparable with a
 *                     forecast for another dose
 */
public record PauseChoice(Integer pauseMinutes, List<PredictionPointDTO> curve, Double peak, Double min,
                          String reason, boolean inFlight) {

    /** A choice made without a pre-bolus in flight. */
    public PauseChoice(Integer pauseMinutes, List<PredictionPointDTO> curve, Double peak, Double min, String reason) {
        this(pauseMinutes, curve, peak, min, reason, false);
    }
}
