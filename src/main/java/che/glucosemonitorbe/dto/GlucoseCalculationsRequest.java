package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder(toBuilder = true)
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
     * Current CGM rate of change [mmol/L per minute], negative when glucose is falling.
     *
     * <p>Hovorka model: the forecast infers plasma glucose from the reading and this trend. When it
     * is null the server takes the trend from the user's stored CGM readings, if they are fresh. A
     * caller whose {@link #currentGlucose} is not the user's current CGM reading (a typed or
     * historical value) must therefore send {@code 0.0}, or that value is given the sensor's trend.</p>
     *
     * <p>OpenAPS model: the short-term momentum term of the prediction path; null means none.</p>
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
