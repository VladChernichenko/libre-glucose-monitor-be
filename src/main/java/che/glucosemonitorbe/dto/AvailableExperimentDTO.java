package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.Experiment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableExperimentDTO {
    private Experiment.Type type;
    private String title;
    private String description;
    private int durationMinutes;
    /** True if all prerequisites are met and the user can start this experiment. */
    private boolean available;
    /** Reason the experiment is locked, if not available. */
    private String lockReason;
    /** Most recent completed result for this type, if any. */
    private Double lastComputedIsf;
    private Double lastComputedCarbRatio;
    private Boolean lastIsStable;
}
