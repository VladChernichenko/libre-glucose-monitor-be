package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;

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
}

