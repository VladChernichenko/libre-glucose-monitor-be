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
    private String sourceName;
    private String sourceUrl;
    private String sourceTitle;
    private String sourceTopic;
    private String evidenceLevel;
}
