package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlucoseCalculationsRequest {
    @NotNull(message = "Current glucose value is required")
    @DecimalMin(value = "0.1", message = "Glucose value must be positive")
    private Double currentGlucose;

    private String userId;
    private Boolean includePredictionFactors;
    private Integer predictionHorizonMinutes;

    /**
     * Client-side time information to replace server LocalDateTime.now()
     */
    private ClientTimeInfo clientTimeInfo;

    /**
     * Optional prospective meal/insulin events that the user has analysed but not yet
     * saved to notes. Included in COB/IOB and prediction path calculations so the
     * Nutrition screen can show a "what-if this meal is eaten now" forecast without
     * requiring persistence first.
     */
    private List<ProspectiveNoteDTO> prospectiveNotes;

    /**
     * Current glucose rate-of-change from the CGM sensor trend arrow (mmol/L per minute).
     * Used to blend short-term momentum into the prediction path so that when glucose is
     * currently flat the near-term prediction does not show an unrealistic steep rise.
     * Null / absent means no momentum term (pure COB/IOB model).
     */
    private Double currentTrendMmolPerMin;

    /**
     * Optional minimum length of the prediction path [min]. The path is
     * {@code min(480, max(default length, this))}; null keeps the default (240 min, longer for
     * long-tail meal patterns). The pre-bolus pause search asks for 240 + its longest pause so
     * every candidate is seen for 240 min after its own meal.
     */
    private Integer predictionPathMinutes;
}
