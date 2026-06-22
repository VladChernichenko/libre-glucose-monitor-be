package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentResultDTO {
    /** ISF result: mmol/L drop per 1 unit of insulin. Null for non-ISF experiments. */
    private Double computedIsf;
    /** Carb Ratio result: mmol/L rise per gram of carbs. Null for non-carb experiments. */
    private Double computedCarbRatio;
    /** Basal Check result: true = stable (<= 1.7 mmol/L delta). */
    private Boolean isStable;
    /** Whether result was automatically saved to UserSettings. */
    private boolean savedToSettings;
    /** Human-readable explanation of the result. */
    private String explanation;
    private ExperimentDTO experiment;
}
