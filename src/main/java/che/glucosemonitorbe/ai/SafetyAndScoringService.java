package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SafetyAndScoringService {
    private static final double HYPO_THRESHOLD_MMOL = 3.9;
    private static final double HYPER_THRESHOLD_MMOL = 10.0;
    private static final double FAST_RISE_THRESHOLD_MMOL = 2.0;

    private final ObjectMapper objectMapper;

    public AiAnalysisResponse finalizeResponse(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            LlmGatewayService.GatewayResult result
    ) {
        String summary = "Glucose has been relatively stable in the selected period.";
        List<AiPatternDTO> patterns = new ArrayList<>();
        List<AiPatternDTO> mistakes = new ArrayList<>();
        List<AiRecommendationDTO> recs = new ArrayList<>();
        double confidence = 0.55;
        String disclaimer = "Educational support only; not a dosing instruction.";

        if (context.getLatestGlucose() > HYPER_THRESHOLD_MMOL) {
            patterns.add(AiPatternDTO.builder().code("HYPER_NOW").description("Current glucose is above target.").severity("medium").build());
            mistakes.add(AiPatternDTO.builder().code("POSSIBLE_LATE_CORRECTION").description("Consider if correction timing was delayed.").severity("low").build());
            recs.add(AiRecommendationDTO.builder().code("CHECK_CORRECTION_WINDOW").text("Review correction factor and avoid stacking corrections too close.").priority("high").build());
            confidence += 0.1;
        }
        if (context.getLatestGlucose() < HYPO_THRESHOLD_MMOL) {
            patterns.add(AiPatternDTO.builder().code("HYPO_NOW").description("Current glucose is below target.").severity("high").build());
            recs.add(AiRecommendationDTO.builder().code("HYPO_PROTOCOL").text("Follow your hypo protocol and recheck glucose soon.").priority("critical").build());
            confidence += 0.15;
        }
        if (context.getDeltaGlucose() > FAST_RISE_THRESHOLD_MMOL) {
            patterns.add(AiPatternDTO.builder().code("FAST_RISE").description("Rapid upward glucose trend.").severity("medium").build());
            recs.add(AiRecommendationDTO.builder().code("POST_MEAL_REVIEW").text("Review meal bolus timing and carb estimate for this meal pattern.").priority("medium").build());
        }
        if (context.getPredictedGlucose2h() != null) {
            patterns.add(AiPatternDTO.builder()
                    .code("PREDICTION_2H")
                    .description("Model 2h prediction is " + context.getPredictedGlucose2h() + " mmol/L.")
                    .severity("low")
                    .build());
        }
        if (context.getAvgPreBolusPauseMinutes() != null) {
            recs.add(AiRecommendationDTO.builder()
                    .code("PRE_BOLUS_PAUSE")
                    .text("Average pre-bolus pause is " + context.getAvgPreBolusPauseMinutes() + " min (latest " +
                            context.getLatestPreBolusPauseMinutes() + " min). Compare with your target pause window.")
                    .priority("medium")
                    .build());
        }
        if (context.getEstimatedCorrectionUnits() != null && context.getEstimatedCorrectionUnits() > 0.0) {
            recs.add(AiRecommendationDTO.builder()
                    .code("CORRECTION_MATH_GUIDE")
                    .text("Correction guidance estimate is ~" + context.getEstimatedCorrectionUnits() +
                            "u using current settings and active insulin. Confirm with your personal plan before acting.")
                    .priority("high")
                    .build());
        }

        // Attempt strict-JSON override from LLM, but keep deterministic fallback if invalid.
        if (result.getRawOutput() != null && !result.getRawOutput().isBlank() && !"{}".equals(result.getRawOutput().trim())) {
            try {
                JsonNode root = objectMapper.readTree(result.getRawOutput());
                if (root.has("summary") && root.get("summary").isTextual()) {
                    summary = root.get("summary").asText();
                }
                if (root.has("confidence") && root.get("confidence").isNumber()) {
                    confidence = Math.max(0.0, Math.min(1.0, root.get("confidence").asDouble()));
                }
                if (root.has("disclaimer") && root.get("disclaimer").isTextual()) {
                    disclaimer = root.get("disclaimer").asText();
                }
                if (root.has("detectedPatterns") && root.get("detectedPatterns").isArray()) {
                    List<AiPatternDTO> llmPatterns = new ArrayList<>();
                    for (JsonNode n : root.get("detectedPatterns")) {
                        llmPatterns.add(AiPatternDTO.builder()
                                .code(asTextOr(n, "code", "PATTERN"))
                                .description(asTextOr(n, "description", ""))
                                .severity(asTextOr(n, "severity", "low"))
                                .build());
                    }
                    if (!llmPatterns.isEmpty()) {
                        patterns = llmPatterns;
                    }
                }
                if (root.has("likelyMistakes") && root.get("likelyMistakes").isArray()) {
                    List<AiPatternDTO> llmMistakes = new ArrayList<>();
                    for (JsonNode n : root.get("likelyMistakes")) {
                        llmMistakes.add(AiPatternDTO.builder()
                                .code(asTextOr(n, "code", "MISTAKE"))
                                .description(asTextOr(n, "description", ""))
                                .severity(asTextOr(n, "severity", "low"))
                                .build());
                    }
                    if (!llmMistakes.isEmpty()) {
                        mistakes = llmMistakes;
                    }
                }
                if (root.has("recommendations") && root.get("recommendations").isArray()) {
                    List<AiRecommendationDTO> llmRecs = new ArrayList<>();
                    for (JsonNode n : root.get("recommendations")) {
                        llmRecs.add(AiRecommendationDTO.builder()
                                .code(asTextOr(n, "code", "RECOMMEND"))
                                .text(asTextOr(n, "text", ""))
                                .priority(asTextOr(n, "priority", "medium"))
                                .build());
                    }
                    if (!llmRecs.isEmpty()) {
                        recs = llmRecs;
                    }
                }
            } catch (Exception ignored) {
                // keep deterministic output
            }
        }

        // Remove potentially unsafe imperative dosing language.
        recs = recs.stream()
                .map(r -> {
                    String safeText = r.getText().replaceAll("(?i)take\\s+\\d+(\\.\\d+)?\\s*u", "review your correction guidance");
                    return AiRecommendationDTO.builder().code(r.getCode()).text(safeText).priority(r.getPriority()).build();
                })
                .toList();

        List<AiInsightEvidenceDTO> evidence = chunks.stream().limit(5)
                .map(c -> AiInsightEvidenceDTO.builder()
                        .chunkId(c.getId().toString())
                        .title(c.getTitle())
                        .conditionTag(c.getConditionTag())
                        .sourceName(c.getSourceName())
                        .sourceUrl(c.getSourceUrl())
                        .sourceTitle(c.getSourceTitle())
                        .sourceTopic(c.getSourceTopic())
                        .evidenceLevel(c.getEvidenceLevel())
                        .build())
                .toList();

        Integer totalTokens = null;
        Integer remaining = null;
        if (result.getPromptTokens() != null || result.getCompletionTokens() != null) {
            int prompt = result.getPromptTokens() == null ? 0 : result.getPromptTokens();
            int completion = result.getCompletionTokens() == null ? 0 : result.getCompletionTokens();
            totalTokens = prompt + completion;
            if (result.getContextWindow() != null) {
                remaining = Math.max(0, result.getContextWindow() - totalTokens);
            }
        }

        return AiAnalysisResponse.builder()
                .summary(summary)
                .detectedPatterns(patterns)
                .likelyMistakes(mistakes)
                .recommendations(recs)
                .evidenceRefs(evidence)
                .confidence(confidence)
                .disclaimer(disclaimer)
                .modelId(result.getModelId())
                .contextWindowTokens(result.getContextWindow())
                .promptTokens(result.getPromptTokens())
                .completionTokens(result.getCompletionTokens())
                .totalTokens(totalTokens)
                .remainingContextTokens(remaining)
                .latencyMs(result.getLatencyMs())
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private String asTextOr(JsonNode node, String key, String fallback) {
        return node.has(key) && node.get(key).isTextual() ? node.get(key).asText() : fallback;
    }
}
