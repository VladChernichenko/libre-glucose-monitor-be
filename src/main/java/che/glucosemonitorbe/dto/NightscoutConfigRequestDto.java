package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NightscoutConfigRequestDto {
    
    @NotBlank(message = "Nightscout URL is required")
    @Pattern(regexp = "https?://.*", message = "Nightscout URL must be a valid HTTP/HTTPS URL")
    @Size(max = 500, message = "Nightscout URL must not exceed 500 characters")
    private String nightscoutUrl;
    
    @Size(max = 255, message = "API Secret must not exceed 255 characters")
    private String apiSecret;
    
    @Size(max = 500, message = "API Token must not exceed 500 characters")
    private String apiToken;
    
    @Builder.Default
    private Boolean isActive = true;
}
