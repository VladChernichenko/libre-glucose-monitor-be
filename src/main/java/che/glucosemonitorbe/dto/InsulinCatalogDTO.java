package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsulinCatalogDTO {
    private String code;
    private String category;
    private String displayName;
    private Integer peakMinutes;
    private Double diaHours;
    private Double halfLifeMinutes;
    private Integer onsetMinutes;
    private String description;
}
