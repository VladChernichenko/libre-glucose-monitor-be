package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.dto.AiAnalysisRequest;
import che.glucosemonitorbe.dto.AiAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class AiInsightService {

    private final ContextAggregatorService contextAggregatorService;
    private final RagRetrieverService ragRetrieverService;
    private final LlmGatewayService llmGatewayService;
    private final SafetyAndScoringService safetyAndScoringService;
    private final AiAnalysisTraceService aiAnalysisTraceService;

    public AiAnalysisResponse analyzeRetrospective(UUID userId, int windowHours) {
        AnalysisContext context = contextAggregatorService.buildContext(userId, windowHours);
        List<ClinicalKnowledgeChunk> chunks = ragRetrieverService.retrieve(context);
        LlmGatewayService.GatewayResult llm = llmGatewayService.generate(context, chunks);
        AiAnalysisResponse response = safetyAndScoringService.finalizeResponse(context, chunks, llm);
        aiAnalysisTraceService.record(userId, windowHours, context, response);
        return response;
    }

    public AiAnalysisResponse analyzeRetrospectiveStream(UUID userId, int windowHours, Consumer<String> tokenConsumer) {
        AnalysisContext context = contextAggregatorService.buildContext(userId, windowHours);
        List<ClinicalKnowledgeChunk> chunks = ragRetrieverService.retrieve(context);
        LlmGatewayService.GatewayResult llm = llmGatewayService.generateStreaming(context, chunks, tokenConsumer);
        AiAnalysisResponse response = safetyAndScoringService.finalizeResponse(context, chunks, llm);
        aiAnalysisTraceService.record(userId, windowHours, context, response);
        return response;
    }

    public LlmGatewayService.GatewayResult streamRetrospectiveMarkdown(UUID userId, int windowHours, Consumer<String> tokenConsumer) {
        return streamRetrospectiveMarkdown(userId, windowHours, tokenConsumer, null, null, null, null);
    }

    public LlmGatewayService.GatewayResult streamRetrospectiveMarkdown(
            UUID userId,
            int windowHours,
            Consumer<String> tokenConsumer,
            String followUpQuestion,
            List<AiAnalysisRequest.AiChatTurnDto> conversationTurns,
            String modelOverride,
            Integer numCtxOverride
    ) {
        AnalysisContext context = contextAggregatorService.buildContext(userId, windowHours);
        List<ClinicalKnowledgeChunk> chunks = ragRetrieverService.retrieve(context);
        return llmGatewayService.generateStreamingMarkdown(
                context,
                chunks,
                tokenConsumer,
                followUpQuestion,
                conversationTurns,
                modelOverride,
                numCtxOverride
        );
    }
}
