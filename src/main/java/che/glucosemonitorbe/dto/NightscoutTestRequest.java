package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NightscoutTestRequest {
    
    @NotBlank(message = "Nightscout URL is required")
    private String nightscoutUrl;
    
    private String apiSecret;
    
    private String apiToken;
}

