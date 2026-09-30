package che.glucosemonitorbe.glycemic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Feature vector of a glycemic event at its onset (spec section 1.3). Stored as JSONB; unknown keys are
 * ignored on read so the set can grow. Tail fields are null until {@code TailResolver} sets them.
 * Units: mmol/L, mmol/L/min, minutes, grams, units of insulin.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventFeatures(
        int v,
        double onsetHour,
        boolean weekend,
        double durationMin,
        double meanResidual,
        double peakResidual,
        double residualSlope,
        double actualSlope,
        double predictedSlope,
        double glucoseAtOnset,
        double iobAtOnset,
        double expectedDrop60,
        double cobAtOnset,
        Double minSinceCarbs,
        Double minSinceBolus,
        boolean activityLogged,
        boolean carbsLogged,
        boolean insulinLogged,
        double sigmaMultiple,
        Double insulinEffectRatio,
        Double tailMinResidual,
        Double minToTailDrop,
        Integer recentNoEffectCount14d) {

    public static final int VERSION = 1;

    public EventFeatures withTail(Double effectRatio, Double tailMin, Double minToDrop, Integer recentNoEffect) {
        return new EventFeatures(v, onsetHour, weekend, durationMin, meanResidual, peakResidual,
                residualSlope, actualSlope, predictedSlope, glucoseAtOnset, iobAtOnset, expectedDrop60,
                cobAtOnset, minSinceCarbs, minSinceBolus, activityLogged, carbsLogged, insulinLogged,
                sigmaMultiple, effectRatio, tailMin, minToDrop, recentNoEffect);
    }
}
