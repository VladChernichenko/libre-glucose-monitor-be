package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.Experiment;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StartExperimentRequest {
    /** Required: which experiment to run. */
    private Experiment.Type type;
    /** Required for CARB_FACTOR: grams of fast-acting carbs the user will consume at T+0. */
    private Double gramsConsumed;
    /** Required for ISF_ONE_UNIT: units of rapid insulin the user will inject at T+0. */
    private Double unitsInjected;
}
