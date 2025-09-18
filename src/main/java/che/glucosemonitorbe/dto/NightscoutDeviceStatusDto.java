package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NightscoutDeviceStatusDto {
    @JsonProperty("_id")
    private String id;
    
    private Long date;
    private String dateString;
    private String device;
    private Integer uploaderBattery;
    
    @JsonProperty("pump")
    private PumpInfo pump;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PumpInfo {
        private BatteryInfo battery;
        private StatusInfo status;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatteryInfo {
        private Integer percent;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusInfo {
        private String status;
    }
}


