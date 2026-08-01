package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response body for {@code POST /api/predict}.
 *
 * <p>Contains the 300-minute glucose prediction curve together with
 * clinical decision-support fields: recommended pre-bolus pause and
 * bolus-wave strategy.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictResponse {

    /**
     * Predicted glucose trajectory - one point every 5 min (0-4 h) or 10 min (4-8 h).
     *
     * <p>Spans the requested horizon from now, extended by {@link #preBolusMinutes} when a
     * pause is recommended, so the curve always covers the full horizon past the meal.</p>
     */
    private List<PredictionPointDTO> curve;

    /**
     * Recommended pre-bolus pause [min]: how long to wait between injecting and eating.
     *
     * <p>Chosen from [0, 5, 10, 15, 20, 25, 30] as the candidate minimising a time-weighted
     * mean deviation from 5.5 mmol/L, scored from the injection onward - the pre-meal
     * interval included, since that is where an over-long pre-bolus causes a hypo.
     * Deviations below 3.9 mmol/L are penalised far more heavily than equivalent
     * hyperglycaemia, so the recommendation is deliberately hypo-averse.</p>
     *
     * <p>0 when no insulin dose was provided. Null when a pre-bolus is already in flight,
     * in which case no recommendation is computed and the measured elapsed time is returned
     * as {@link #observedPreBolusMinutes} instead.</p>
     */
    private Integer preBolusMinutes;

    /**
     * Measured minutes between the already-logged pre-bolus and this prediction anchor.
     * Non-null only when a pre-bolus was in flight, in which case
     * {@link #preBolusMinutes} is null - no recommendation is computed.
     */
    private Integer observedPreBolusMinutes;

    /**
     * Recommended bolus strategy:
     * <ul>
     *   <li>{@code "NORMAL"}      - standard meal bolus</li>
     *   <li>{@code "SQUARE_WAVE"} - extended / dual-wave bolus for high-fat or high-protein meals
     *       where a late glucose rise (3-6 h) is expected from gluconeogenesis or delayed emptying</li>
     * </ul>
     */
    private String bolusStrategy;

    /** Effective tMaxG [min] used in the simulation after macro modulation. */
    @JsonProperty("tMaxGUsed")
    private Double tMaxGUsed;

    /** Weighted Elashoff β coefficient (1.05 = pure carbs, 2.2 = pure fat). */
    private Double betaWeighted;
}
