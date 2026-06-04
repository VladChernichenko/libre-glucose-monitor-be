package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordReadingRequest {
    private Double glucoseMmol;
    private Integer minutesElapsed;
    /** Optional human-readable label, e.g. "Baseline", "T+30min". */
    private String label;
}
