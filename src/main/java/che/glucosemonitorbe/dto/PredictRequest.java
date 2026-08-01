package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
    private Double currentGlucose;      // mmol/L - current CGM reading

    /**
     * Prospective rapid-acting bolus [U] at t = 0.
     *
     * <p><b>Ignored when a pre-bolus is already in flight.</b> If {@link #insulinLoggedAt}
     * (or an auto-detected pre-bolus note) resolves to a logged dose and this value disagrees
     * with that note's units by more than 0.05 U, the server logs a warning and simulates the
     * note's units - the database is the source of truth for what was actually injected. The
     * difference is not simulated on top: send a second dose as its own note if more insulin
     * was really given.</p>
     */
    @DecimalMin(value = "0.0", message = "insulinDose must be non-negative")
    private Double insulinDose;         // units - prospective rapid-acting bolus at t=0

    @DecimalMin(value = "0.0", message = "carbs must be non-negative")
    private Double carbs;               // g - digestible carbohydrates

    @DecimalMin(value = "0.0", message = "protein must be non-negative")
    private Double protein;             // g

    @DecimalMin(value = "0.0", message = "fat must be non-negative")
    private Double fat;                 // g

    @DecimalMin(value = "0.0", message = "fiber must be non-negative")
    private Double fiber;               // g - slows gastric emptying via viscosity

    /**
     * Prediction horizon [min]. Defaults to 300.
     * Clamped to [60, 480] server-side.
     */
    @Min(60) @Max(480)
    private Integer horizonMinutes;

    // -- FPU type-aware fields (populated by the YOLO vision service) ----------

    /**
     * Effective LCT-fat grams after excluding MCT fat [g].
     * When absent, falls back to {@code fat} (conservative: all fat counted as LCT).
     */
    @DecimalMin(value = "0.0", message = "lctFatG must be non-negative")
    private Double lctFatG;

    /**
     * Protein-weighted FPU onset delay [min].
     * When absent, falls back to the server-side default of 90 min (Warsaw Protocol).
     */
    @Min(0) @Max(480)
    private Integer fpuOnsetMin;

    /**
     * Protein-weighted gluconeogenic fraction [0.0-1.0].
     * When absent, falls back to 0.50 (50 % of protein kcal -> slow glucose).
     */
    @DecimalMin(value = "0.0", message = "gluconeogenicFraction must be non-negative")
    private Double gluconeogenicFraction;

    /** IANA timezone string (e.g. "Europe/Berlin"). Used for timestamp alignment. */
    private String clientTimezone;

    /**
     * When the pre-bolus note was written, if the client has already logged the dose
     * and its timer is running. When present and it matches a stored bolus note, the
     * server treats the dose as already in history and does not add it again.
     */
    private LocalDateTime insulinLoggedAt;
}
