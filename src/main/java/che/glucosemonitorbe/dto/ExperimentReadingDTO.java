package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentReadingDTO {
    private UUID id;
    private LocalDateTime recordedAt;
    private Double glucoseMmol;
    private Integer minutesElapsed;
    private String label;
}
