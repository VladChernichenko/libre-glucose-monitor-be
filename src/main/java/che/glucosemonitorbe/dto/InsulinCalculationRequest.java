package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsulinCalculationRequest {

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("300.0")
    private Double carbs;

    /**
     * mmol/L. The 33.0 ceiling is the mg/dL guard: any plausible mg/dL reading (70-400)
     * exceeds the physiological mmol/L range, so the unit mistake is rejected rather than
     * guessed at.
     */
    @NotNull
    @DecimalMin("1.0")
    @DecimalMax("33.0")
    private Double currentGlucose;

    @NotNull
    @DecimalMin("4.0")
    @DecimalMax("10.0")
    private Double targetGlucose;

    @DecimalMin("0.0")
    @DecimalMax("50.0")
    private Double activeInsulin;

    private String mealType;
    private String userId;
    
    /**
     * Client-side time information to replace server LocalDateTime.now()
     */
    private ClientTimeInfo clientTimeInfo;
}
