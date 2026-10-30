package che.glucosemonitorbe.assistant.advice;

import che.glucosemonitorbe.dto.PredictionPointDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Fast carbs in advance on a falling forecast (spec §6.13). All glucose in mmol/L, all times in the
 * user's wall time (as the forecast's own timestamps).
 *
 * @param state        {@link State#TAKE_NOW}: take {@code grams} now; {@link State#STILL_LANDING}:
 *                     fast carbs were logged in the last 15 min, so no new grams are advised
 * @param grams        advised fast carbs [g]; null when {@link State#STILL_LANDING}
 * @param minWithout   forecast minimum over the next 2 h without new carbs
 * @param minWithoutAt when that minimum is reached
 * @param minWith      forecast minimum over the next 2 h with {@code grams} taken now; null when
 *                     {@link State#STILL_LANDING}
 * @param curveWithout the dashboard forecast (no new carbs)
 * @param curveWith    the forecast with {@code grams} taken now; null when {@link State#STILL_LANDING}
 * @param recheckAt    when to look again: now + 15 min, now + 10 min when even 40 g is not enough,
 *                     or the logged fast carbs' time + 15 min when {@link State#STILL_LANDING}
 */
public record CarbAdvice(
        State state,
        Integer grams,
        double minWithout,
        LocalDateTime minWithoutAt,
        Double minWith,
        List<PredictionPointDTO> curveWithout,
        List<PredictionPointDTO> curveWith,
        LocalDateTime recheckAt) {

    public enum State { TAKE_NOW, STILL_LANDING }

    /** This advice as "still landing" after fast carbs logged at {@code tookAt}: no grams, no with-curve. */
    CarbAdvice stillLanding(LocalDateTime tookAt) {
        return new CarbAdvice(State.STILL_LANDING, null, minWithout, minWithoutAt, null,
                curveWithout, null, tookAt.plusMinutes(CarbAdviceService.LANDING_MINUTES));
    }
}
