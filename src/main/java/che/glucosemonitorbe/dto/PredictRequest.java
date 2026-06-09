package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/predict}.
 *
 * <p>Accepts the current CGM reading, a prospective bolus, and macronutrient
 * breakdown of the meal about to be eaten. The engine runs a Hovorka ODE
 * simulation with Dalla Man / Elashoff gastric modulation and returns a
 * 300-minute prediction curve together with the optimal pre-bolus pause.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictRequest {

    @NotNull(message = "currentGlucose is required")
    @DecimalMin(value = "0.1", message = "currentGlucose must be positive")
    private Double currentGlucose;      // mmol/L — current CGM reading

    @DecimalMin(value = "0.0", message = "insulinDose must be non-negative")
    private Double insulinDose;         // units — prospective rapid-acting bolus at t=0

    @DecimalMin(value = "0.0", message = "carbs must be non-negative")
    private Double carbs;               // g — digestible carbohydrates

    @DecimalMin(value = "0.0", message = "protein must be non-negative")
    private Double protein;             // g

    @DecimalMin(value = "0.0", message = "fat must be non-negative")
    private Double fat;                 // g

    @DecimalMin(value = "0.0", message = "fiber must be non-negative")
    private Double fiber;               // g — slows gastric emptying via viscosity

    /**
     * Prediction horizon [min]. Defaults to 300.
     * Clamped to [60, 480] server-side.
     */
    @Min(60) @Max(480)
    private Integer horizonMinutes;

    /** IANA timezone string (e.g. "Europe/Berlin"). Used for timestamp alignment. */
    private String clientTimezone;
}
