package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NightscoutConfigDto {
    private String baseUrl;
    private String apiSecret;
    private String token;
}


