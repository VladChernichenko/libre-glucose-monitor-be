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

    /** Context window for assistant chat replies (hours of notes and CGM before "now"). */
    static final int ASSISTANT_WINDOW_HOURS = 12;

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

    /**
     * Assistant chat question path: builds the current context (same basis as the retrospective
     * stream), retrieves passages, and streams a reply in {@code language} ("ru"/"en") using the
     * given recent chat turns (oldest first). No provider or model override.
     */
    public LlmGatewayService.GatewayResult streamAssistantReply(
            UUID userId,
            String question,
            List<AiAnalysisRequest.AiChatTurnDto> recentTurns,
            String language,
            Consumer<String> tokenConsumer
    ) {
        AnalysisContext context = contextAggregatorService.buildContext(userId, ASSISTANT_WINDOW_HOURS);
        List<ClinicalKnowledgeChunk> chunks = ragRetrieverService.retrieve(context);
        return llmGatewayService.streamChatReply(context, chunks, question, recentTurns, language, tokenConsumer);
    }

    public LlmGatewayService.GatewayResult streamRetrospectiveMarkdown(UUID userId, int windowHours, Consumer<String> tokenConsumer) {
        return streamRetrospectiveMarkdown(userId, windowHours, tokenConsumer, null, null, null, null, null);
    }

    public LlmGatewayService.GatewayResult streamRetrospectiveMarkdown(
            UUID userId,
            int windowHours,
            Consumer<String> tokenConsumer,
            String followUpQuestion,
            List<AiAnalysisRequest.AiChatTurnDto> conversationTurns,
            String modelOverride,
            Integer numCtxOverride,
            String providerOverride
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
                numCtxOverride,
                providerOverride
        );
    }
}
