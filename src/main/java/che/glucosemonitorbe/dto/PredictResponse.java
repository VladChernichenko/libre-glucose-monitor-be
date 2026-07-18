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

    /** Predicted glucose trajectory - one point every 5 min (0-4 h) or 10 min (4-8 h). */
    private List<PredictionPointDTO> curve;

    /**
     * Recommended pre-bolus pause [min] that minimises ∫(G(t) − 5.5)² dt over the horizon.
     * 0 when no insulin dose was provided.
     */
    private Integer preBolusMinutes;

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
