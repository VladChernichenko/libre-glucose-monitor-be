package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NightscoutTestResponseDto {

    private boolean ok;

    private String message;
}
