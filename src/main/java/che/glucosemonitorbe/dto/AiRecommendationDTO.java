package che.glucosemonitorbe.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRecommendationDTO {
    private String code;
    private String text;
    private String priority;
}
