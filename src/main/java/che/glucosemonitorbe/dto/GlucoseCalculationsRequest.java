package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlucoseCalculationsRequest {
    @NotNull(message = "Current glucose value is required")
    @Min(value = 0, message = "Glucose value must be positive")
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
}

