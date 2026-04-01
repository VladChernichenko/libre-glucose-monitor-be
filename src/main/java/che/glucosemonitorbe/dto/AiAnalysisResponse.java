package che.glucosemonitorbe.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAnalysisResponse {
    private String summary;
    private List<AiPatternDTO> detectedPatterns;
    private List<AiPatternDTO> likelyMistakes;
    private List<AiRecommendationDTO> recommendations;
    private List<AiInsightEvidenceDTO> evidenceRefs;
    private double confidence;
    private String disclaimer;
    private String modelId;
    private Integer contextWindowTokens;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer remainingContextTokens;
    private long latencyMs;
    private LocalDateTime generatedAt;
}
