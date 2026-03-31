package che.glucosemonitorbe.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiInsightEvidenceDTO {
    private String chunkId;
    private String title;
    private String conditionTag;
}
