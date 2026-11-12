package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionPointDTO {
    private LocalDateTime timestamp;
    private Double predictedGlucose;
    private Double carbAbsorptionEffect;
    private Double insulinActivityEffect;
    private String absorptionMode;

    // -- Probabilistic band (digital-twin uncertainty) -------------------------
    /** Lower bound of the confidence band [mmol/L] - predictedGlucose − z*σ(horizon). */
    private Double predictedGlucoseLower;
    /** Upper bound of the confidence band [mmol/L] - predictedGlucose + z*σ(horizon). */
    private Double predictedGlucoseUpper;
    /** Predictive standard deviation at this horizon [mmol/L]. */
    private Double uncertaintySd;
    /** Confidence covered by [lower, upper], e.g. 0.90 for a 90% band. */
    private Double confidenceLevel;

    /**
     * {@link #predictedGlucose} before rounding to 0.1 mmol/L. Server-side only: the calibration
     * fit differentiates the prediction numerically with a step far smaller than the rounding, so
     * it must read this and not the displayed value. Null on paths that do not set it.
     */
    @JsonIgnore
    private Double rawPredictedGlucose;
}
