package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.Experiment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentDTO {
    private UUID id;
    private UUID userId;
    private Experiment.Type type;
    private Experiment.Status status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Double gramsConsumed;
    private Double unitsInjected;
    private Double computedIsf;
    private Double computedCarbRatio;
    private Boolean isStable;
    private String resultNotes;
    private LocalDateTime createdAt;
    private List<ExperimentReadingDTO> readings;
}
