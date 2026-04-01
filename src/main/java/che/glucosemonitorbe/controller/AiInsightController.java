package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.ai.AiInsightService;
import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.dto.AiAnalysisRequest;
import che.glucosemonitorbe.dto.AiAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import che.glucosemonitorbe.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai-insights")
@RequiredArgsConstructor
public class AiInsightController {

    private final AiInsightService aiInsightService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @PostMapping("/retrospective")
    public ResponseEntity<AiAnalysisResponse> retrospective(
            Authentication authentication,
            @Valid @RequestBody(required = false) AiAnalysisRequest request
    ) {
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        int window = request == null || request.getWindowHours() == null ? 12 : request.getWindowHours();
        return ResponseEntity.ok(aiInsightService.analyzeRetrospective(userId, window));
    }

    @PostMapping(value = "/retrospective/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> retrospectiveStream(
            Authentication authentication,
            @Valid @RequestBody(required = false) AiAnalysisRequest request
    ) {
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        int window = request == null || request.getWindowHours() == null ? 12 : request.getWindowHours();
        String followUpQuestion = request == null ? null : request.getFollowUpQuestion();
        List<AiAnalysisRequest.AiChatTurnDto> conversationTurns = request == null ? null : request.getConversationTurns();
        String modelOverride = request == null ? null : request.getModel();
        Integer numCtxOverride = request == null ? null : request.getNumCtx();

        StreamingResponseBody body = outputStream -> {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                try {
                    LlmGatewayService.GatewayResult usage = aiInsightService.streamRetrospectiveMarkdown(
                            userId, window, token -> {
                        try {
                            writeEvent(writer, Map.of("type", "token", "token", token));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }, followUpQuestion, conversationTurns, modelOverride, numCtxOverride);
                    Integer prompt = usage.getPromptTokens();
                    Integer completion = usage.getCompletionTokens();
                    Integer contextWindow = usage.getContextWindow();
                    Integer total = (prompt == null && completion == null) ? null : (prompt == null ? 0 : prompt) + (completion == null ? 0 : completion);
                    Integer remaining = (contextWindow == null || total == null) ? null : Math.max(0, contextWindow - total);
                    writeEvent(writer, Map.of(
                            "type", "done",
                            "promptTokens", prompt == null ? 0 : prompt,
                            "completionTokens", completion == null ? 0 : completion,
                            "totalTokens", total == null ? 0 : total,
                            "contextWindowTokens", contextWindow == null ? 0 : contextWindow,
                            "remainingContextTokens", remaining == null ? 0 : remaining
                    ));
                } catch (Exception e) {
                    writeEvent(writer, Map.of("type", "error", "message", "AI stream failed"));
                }
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(body);
    }

    private void writeEvent(BufferedWriter writer, Object event) throws IOException {
        writer.write(objectMapper.writeValueAsString(event));
        writer.write("\n");
        writer.flush();
    }
}
