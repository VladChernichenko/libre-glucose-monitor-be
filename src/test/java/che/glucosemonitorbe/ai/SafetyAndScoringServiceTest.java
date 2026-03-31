package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.dto.AiAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SafetyAndScoringServiceTest {

    private final SafetyAndScoringService service = new SafetyAndScoringService(new ObjectMapper());

    @Test
    void fallbackRulesShouldProduceRecommendationForHighGlucose() {
        AnalysisContext context = AnalysisContext.builder()
                .userId(UUID.randomUUID())
                .latestGlucose(11.2)
                .avgGlucose(9.8)
                .deltaGlucose(3.0)
                .notes(List.of())
                .build();

        ClinicalKnowledgeChunk chunk = ClinicalKnowledgeChunk.builder()
                .id(UUID.randomUUID())
                .title("Hyper guidance")
                .conditionTag("HYPER")
                .content("Avoid stacking corrections")
                .active(true)
                .build();

        LlmGatewayService.GatewayResult llm = LlmGatewayService.GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput("{}")
                .latencyMs(10)
                .build();

        AiAnalysisResponse resp = service.finalizeResponse(context, List.of(chunk), llm);

        assertNotNull(resp);
        assertFalse(resp.getRecommendations().isEmpty());
        assertTrue(resp.getRecommendations().stream().anyMatch(r -> r.getCode().equals("CHECK_CORRECTION_WINDOW")));
        assertEquals("rules-only", resp.getModelId());
    }
}
