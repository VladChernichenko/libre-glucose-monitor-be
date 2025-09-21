package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LogoutRequest {
    
    @NotBlank(message = "Access token is required")
    private String accessToken;
    
    private String refreshToken; // Optional - if provided, will also be blacklisted
}
