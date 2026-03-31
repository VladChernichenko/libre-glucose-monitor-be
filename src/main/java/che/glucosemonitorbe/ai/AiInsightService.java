package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
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

    public void streamRetrospectiveMarkdown(UUID userId, int windowHours, Consumer<String> tokenConsumer) {
        AnalysisContext context = contextAggregatorService.buildContext(userId, windowHours);
        List<ClinicalKnowledgeChunk> chunks = ragRetrieverService.retrieve(context);
        llmGatewayService.generateStreamingMarkdown(context, chunks, tokenConsumer);
    }
}
