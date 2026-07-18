package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NightscoutEntryDto {
    @JsonProperty("_id")
    private String id;
    
    private Integer sgv; // Glucose value in mg/dL
    private Long date; // Unix timestamp
    private String dateString;
    private Integer trend;
    private String direction;
    private String device;
    private String type;
    private Integer utcOffset;
    private String sysTime;
}

