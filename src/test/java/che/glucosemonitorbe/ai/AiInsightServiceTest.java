package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.dto.AiAnalysisRequest;
import che.glucosemonitorbe.dto.AiAnalysisResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiInsightServiceTest {

    @Mock ContextAggregatorService contextAggregatorService;
    @Mock RagRetrieverService ragRetrieverService;
    @Mock LlmGatewayService llmGatewayService;
    @Mock SafetyAndScoringService safetyAndScoringService;
    @Mock AiAnalysisTraceService aiAnalysisTraceService;

    @InjectMocks AiInsightService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("analyzeRetrospective calls all collaborators in pipeline order and returns result")
    void analyzeRetrospective_fullPipeline() {
        AnalysisContext ctx = AnalysisContext.builder().userId(userId).build();
        List<ClinicalKnowledgeChunk> chunks = List.of(chunk("HYPER"));
        LlmGatewayService.GatewayResult llmResult = LlmGatewayService.GatewayResult.builder()
                .modelId("test-model").rawOutput("{}").latencyMs(50).build();
        AiAnalysisResponse expected = AiAnalysisResponse.builder().modelId("test-model").build();

        when(contextAggregatorService.buildContext(userId, 12)).thenReturn(ctx);
        when(ragRetrieverService.retrieve(ctx)).thenReturn(chunks);
        when(llmGatewayService.generate(ctx, chunks)).thenReturn(llmResult);
        when(safetyAndScoringService.finalizeResponse(ctx, chunks, llmResult)).thenReturn(expected);

        AiAnalysisResponse result = service.analyzeRetrospective(userId, 12);

        assertThat(result).isSameAs(expected);
        // verify ordering through sequential mock verifications
        inOrder(contextAggregatorService, ragRetrieverService, llmGatewayService, safetyAndScoringService, aiAnalysisTraceService);
        verify(aiAnalysisTraceService).record(eq(userId), eq(12), eq(ctx), eq(expected));
    }

    @Test
    @DisplayName("analyzeRetrospectiveStream uses generateStreaming and returns finalized response")
    void analyzeRetrospectiveStream_usesStreamingGenerate() {
        AnalysisContext ctx = AnalysisContext.builder().userId(userId).build();
        List<ClinicalKnowledgeChunk> chunks = List.of();
        LlmGatewayService.GatewayResult llmResult = LlmGatewayService.GatewayResult.builder()
                .modelId("stream-model").rawOutput("").latencyMs(100).build();
        AiAnalysisResponse expected = AiAnalysisResponse.builder().modelId("stream-model").build();

        when(contextAggregatorService.buildContext(userId, 6)).thenReturn(ctx);
        when(ragRetrieverService.retrieve(ctx)).thenReturn(chunks);
        when(llmGatewayService.generateStreaming(eq(ctx), eq(chunks), any())).thenReturn(llmResult);
        when(safetyAndScoringService.finalizeResponse(ctx, chunks, llmResult)).thenReturn(expected);

        Consumer<String> consumer = token -> {};
        AiAnalysisResponse result = service.analyzeRetrospectiveStream(userId, 6, consumer);

        assertThat(result).isSameAs(expected);
        verify(llmGatewayService).generateStreaming(eq(ctx), eq(chunks), eq(consumer));
        verify(llmGatewayService, never()).generate(any(), any());
    }

    @Test
    @DisplayName("streamRetrospectiveMarkdown delegates to generateStreamingMarkdown with all params")
    void streamRetrospectiveMarkdown_withFollowUp_delegatesCorrectly() {
        AnalysisContext ctx = AnalysisContext.builder().userId(userId).build();
        List<ClinicalKnowledgeChunk> chunks = List.of();
        LlmGatewayService.GatewayResult llmResult = LlmGatewayService.GatewayResult.builder()
                .modelId("md-model").rawOutput("## Summary").latencyMs(200).build();

        when(contextAggregatorService.buildContext(userId, 24)).thenReturn(ctx);
        when(ragRetrieverService.retrieve(ctx)).thenReturn(chunks);
        when(llmGatewayService.generateStreamingMarkdown(eq(ctx), eq(chunks), any(), eq("What should I do?"),
                isNull(), eq("custom-model"), eq(4096))).thenReturn(llmResult);

        Consumer<String> consumer = token -> {};
        LlmGatewayService.GatewayResult result = service.streamRetrospectiveMarkdown(
                userId, 24, consumer, "What should I do?", null, "custom-model", 4096);

        assertThat(result).isSameAs(llmResult);
    }

    @Test
    @DisplayName("streamRetrospectiveMarkdown no-arg overload passes nulls for optional params")
    void streamRetrospectiveMarkdown_noArgOverload_passesNulls() {
        AnalysisContext ctx = AnalysisContext.builder().userId(userId).build();
        List<ClinicalKnowledgeChunk> chunks = List.of();
        LlmGatewayService.GatewayResult llmResult = LlmGatewayService.GatewayResult.builder()
                .modelId("rules-only").rawOutput("").latencyMs(5).build();

        when(contextAggregatorService.buildContext(userId, 12)).thenReturn(ctx);
        when(ragRetrieverService.retrieve(ctx)).thenReturn(chunks);
        when(llmGatewayService.generateStreamingMarkdown(eq(ctx), eq(chunks), any(),
                isNull(), isNull(), isNull(), isNull())).thenReturn(llmResult);

        service.streamRetrospectiveMarkdown(userId, 12, token -> {});

        verify(llmGatewayService).generateStreamingMarkdown(
                eq(ctx), eq(chunks), any(), isNull(), isNull(), isNull(), isNull());
    }

    // ---- helper ----

    private ClinicalKnowledgeChunk chunk(String tag) {
        return ClinicalKnowledgeChunk.builder()
                .id(UUID.randomUUID())
                .conditionTag(tag)
                .title("title")
                .content("content")
                .active(true)
                .build();
    }
}
