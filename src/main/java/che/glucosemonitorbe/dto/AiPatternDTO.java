package che.glucosemonitorbe.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPatternDTO {
    private String code;
    private String description;
    private String severity;
}
