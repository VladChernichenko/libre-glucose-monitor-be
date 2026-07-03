package che.glucosemonitorbe.dto;

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

    // ── Probabilistic band (digital-twin uncertainty) ─────────────────────────
    /** Lower bound of the confidence band [mmol/L] — predictedGlucose − z·σ(horizon). */
    private Double predictedGlucoseLower;
    /** Upper bound of the confidence band [mmol/L] — predictedGlucose + z·σ(horizon). */
    private Double predictedGlucoseUpper;
    /** Predictive standard deviation at this horizon [mmol/L]. */
    private Double uncertaintySd;
    /** Confidence covered by [lower, upper], e.g. 0.90 for a 90% band. */
    private Double confidenceLevel;
}
