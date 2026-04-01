package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NightscoutTestRequestDto {

    @NotBlank(message = "Nightscout URL is required")
    private String nightscoutUrl;

    private String nightscoutApiSecret;

    private String nightscoutApiToken;
}
