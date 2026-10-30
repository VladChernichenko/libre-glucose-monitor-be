package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.dto.AiAnalysisRequest;
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

    /**
     * Assistant chat question path: builds the current context, retrieves passages, and streams a
     * reply in {@code language} ("ru"/"en") using the given recent chat turns (oldest first).
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
}
