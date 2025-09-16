package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NightscoutAverageDto {
    private Double averageGlucose; // Average glucose in mg/dL
    private Double averageGlucoseMmol; // Average glucose in mmol/L
    private Integer totalReadings; // Total number of readings used
    private String timeRange; // e.g., "24h", "7d"
    private Long startTime; // Unix timestamp of start
    private Long endTime; // Unix timestamp of end
    private String unit; // "mg/dL" or "mmol/L"
}
