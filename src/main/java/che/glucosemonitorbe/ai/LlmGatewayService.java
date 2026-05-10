package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.dto.AiAnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGatewayService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FULL_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy 'at' HH:mm");
    private static final String MARKDOWN_FALLBACK_MESSAGE =
            "## Summary\nAI provider is currently unavailable.\n\n"
                    + "## Detected patterns\n- Unable to generate model-backed analysis right now.\n\n"
                    + "## Likely mistakes\n- None can be inferred without model output.\n\n"
                    + "## Recommendations\n- Check Ollama availability and selected model.\n"
                    + "- Retry analysis in a few seconds.\n\n"
                    + "## Disclaimer\nThis is a fallback response due to temporary AI connectivity issues.";

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.ai.ollama.enabled:true}")
    private boolean ollamaEnabled;

    @Value("${app.ai.ollama.url:http://localhost:11434/api/generate}")
    private String ollamaUrl;

    @Value("${app.ai.ollama.model:glm-5:cloud}")
    private String ollamaModel;

    @Value("${app.ai.ollama.api-key:}")
    private String ollamaApiKey;

    @Value("${app.ai.ollama.num-ctx:8192}")
    private int ollamaNumCtx;

    @Value("${app.ai.remote.enabled:false}")
    private boolean remoteEnabled;

    @Value("${app.ai.remote.url:}")
    private String remoteUrl;

    public GatewayResult generate(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) {
        long start = System.currentTimeMillis();

        if (ollamaEnabled) {
            try {
                OllamaResponse response = callOllama(context, chunks);
                return GatewayResult.builder()
                        .modelId("ollama:" + ollamaModel)
                        .rawOutput(response.content())
                        .promptTokens(response.usage().promptTokens())
                        .completionTokens(response.usage().completionTokens())
                        .contextWindow(ollamaNumCtx)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception e) {
                log.warn("[Ollama] sync/json call failed, falling back. reason={}", e.getMessage());
            }
        }

        if (remoteEnabled && remoteUrl != null && !remoteUrl.isBlank()) {
            try {
                String response = callRemote(context, chunks);
                return GatewayResult.builder()
                        .modelId("remote")
                        .rawOutput(response)
                        .contextWindow(ollamaNumCtx)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception ignored) {
                // fallback
            }
        }

        return GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput("{}")
                .contextWindow(ollamaNumCtx)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    public GatewayResult generateStreaming(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            Consumer<String> tokenConsumer
    ) {
        long start = System.currentTimeMillis();

        if (ollamaEnabled) {
            try {
                OllamaResponse response = callOllamaStreaming(context, chunks, tokenConsumer);
                return GatewayResult.builder()
                        .modelId("ollama:" + ollamaModel)
                        .rawOutput(response.content)
                        .promptTokens(response.usage().promptTokens())
                        .completionTokens(response.usage().completionTokens())
                        .contextWindow(ollamaNumCtx)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception e) {
                log.warn("[Ollama] stream/json call failed, falling back. reason={}", e.getMessage());
            }
        }

        if (remoteEnabled && remoteUrl != null && !remoteUrl.isBlank()) {
            try {
                String response = callRemote(context, chunks);
                if (tokenConsumer != null && !response.isBlank()) {
                    tokenConsumer.accept(response);
                }
                return GatewayResult.builder()
                        .modelId("remote")
                        .rawOutput(response)
                        .contextWindow(ollamaNumCtx)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception ignored) {
                // fallback
            }
        }

        return GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput("{}")
                .contextWindow(ollamaNumCtx)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    public GatewayResult generateStreamingMarkdown(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            Consumer<String> tokenConsumer
    ) {
        return generateStreamingMarkdown(context, chunks, tokenConsumer, null, null, null, null);
    }

    public GatewayResult generateStreamingMarkdown(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            Consumer<String> tokenConsumer,
            String followUpQuestion,
            List<AiAnalysisRequest.AiChatTurnDto> conversationTurns,
            String modelOverride,
            Integer numCtxOverride
    ) {
        long start = System.currentTimeMillis();
        String effectiveModel = resolveModel(modelOverride);
        int effectiveNumCtx = resolveNumCtx(numCtxOverride);

        if (ollamaEnabled) {
            try {
                OllamaResponse response = callOllamaStreamingMarkdown(
                        context, chunks, tokenConsumer, followUpQuestion, conversationTurns, effectiveModel, effectiveNumCtx);
                return GatewayResult.builder()
                        .modelId("ollama:" + effectiveModel)
                        .rawOutput(response.content)
                        .promptTokens(response.usage().promptTokens())
                        .completionTokens(response.usage().completionTokens())
                        .contextWindow(effectiveNumCtx)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception e) {
                log.warn("[Ollama] stream/markdown call failed, falling back. reason={}", e.getMessage());
            }
        }

        if (tokenConsumer != null) {
            tokenConsumer.accept(MARKDOWN_FALLBACK_MESSAGE);
        }
        return GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput(MARKDOWN_FALLBACK_MESSAGE)
                .contextWindow(effectiveNumCtx)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    private OllamaResponse callOllama(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) throws Exception {
        String prompt = buildJsonPrompt(context, chunks);
        String json = buildOllamaPayload(prompt, false, true);

        log.debug("[Ollama] model={} url={} numCtx={}", ollamaModel, ollamaUrl, ollamaNumCtx);
        log.debug("[Ollama] PROMPT (sync/json):\n{}", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applyOllamaAuth(headers);
        ResponseEntity<String> resp = restTemplate.exchange(
                ollamaUrl,
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class
        );
        JsonNode root = objectMapper.readTree(resp.getBody());
        JsonNode responseNode = root.get("response");
        if (responseNode == null) {
            log.warn("[Ollama] Response body missing `response` field. Raw body: {}", resp.getBody());
            throw new RestClientException("Ollama response missing `response` field");
        }
        Usage usage = extractUsage(root);
        String extracted = extractJsonObject(responseNode.asText());
        log.debug("[Ollama] RESPONSE (sync/json) promptTokens={} completionTokens={}:\n{}",
                usage.promptTokens(), usage.completionTokens(), extracted);
        return new OllamaResponse(extracted, usage);
    }

    private OllamaResponse callOllamaStreaming(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            Consumer<String> tokenConsumer
    ) throws Exception {
        String prompt = buildJsonPrompt(context, chunks);
        String payload = buildOllamaPayload(prompt, true, true);

        log.debug("[Ollama] model={} url={} numCtx={}", ollamaModel, ollamaUrl, ollamaNumCtx);
        log.debug("[Ollama] PROMPT (stream/json):\n{}", prompt);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(ollamaUrl))
                .header("Content-Type", "application/json");
        applyOllamaAuth(requestBuilder);
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        StringBuilder fullResponse = new StringBuilder();
        Usage usage = new Usage(null, null);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode chunk = objectMapper.readTree(line);
                String token = chunk.has("response") ? chunk.get("response").asText("") : "";
                if (!token.isEmpty()) {
                    fullResponse.append(token);
                    if (tokenConsumer != null) {
                        tokenConsumer.accept(token);
                    }
                }
                if (chunk.has("done") && chunk.get("done").asBoolean(false)) {
                    usage = extractUsage(chunk);
                    break;
                }
            }
        }

        String extracted = extractJsonObject(fullResponse.toString());
        log.debug("[Ollama] RESPONSE (stream/json) promptTokens={} completionTokens={}:\n{}",
                usage.promptTokens(), usage.completionTokens(), extracted);
        return new OllamaResponse(extracted, usage);
    }

    private OllamaResponse callOllamaStreamingMarkdown(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            Consumer<String> tokenConsumer,
            String followUpQuestion,
            List<AiAnalysisRequest.AiChatTurnDto> conversationTurns,
            String modelOverride,
            int numCtxOverride
    ) throws Exception {
        String prompt = buildMarkdownPrompt(context, chunks, followUpQuestion, conversationTurns);
        String payload = buildOllamaPayload(prompt, true, false, modelOverride, numCtxOverride);

        log.debug("[Ollama] model={} url={} numCtx={}", modelOverride, ollamaUrl, numCtxOverride);
        log.debug("[Ollama] PROMPT (stream/markdown):\n{}", prompt);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(ollamaUrl))
                .header("Content-Type", "application/json");
        applyOllamaAuth(requestBuilder);
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        StringBuilder fullResponse = new StringBuilder();
        Usage usage = new Usage(null, null);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode chunk = objectMapper.readTree(line);
                String token = chunk.has("response") ? chunk.get("response").asText("") : "";
                if (!token.isEmpty()) {
                    fullResponse.append(token);
                    if (tokenConsumer != null) {
                        tokenConsumer.accept(token);
                    }
                }
                if (chunk.has("done") && chunk.get("done").asBoolean(false)) {
                    usage = extractUsage(chunk);
                    break;
                }
            }
        }

        log.debug("[Ollama] RESPONSE (stream/markdown) promptTokens={} completionTokens={}:\n{}",
                usage.promptTokens(), usage.completionTokens(), fullResponse);
        return new OllamaResponse(fullResponse.toString(), usage);
    }

    private String callRemote(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = objectMapper.createObjectNode()
                .put("context", buildJsonPrompt(context, chunks))
                .toString();
        ResponseEntity<String> resp = restTemplate.exchange(
                remoteUrl,
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                String.class
        );
        return resp.getBody() == null ? "{}" : resp.getBody();
    }

    private String buildJsonPrompt(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) {
        String refs = chunks.stream()
                .map(this::toReferenceLine)
                .collect(Collectors.joining("\n"));
        String notesBlock = formatRecentNotes(context);
        String predictionMathBlock = formatPredictionMath(context);

        return "You are a glucose assistant. Return ONLY strict JSON object with keys: "
                + "summary:string, detectedPatterns:array[{code,description,severity}], "
                + "likelyMistakes:array[{code,description,severity}], "
                + "recommendations:array[{code,text,priority}], confidence:number(0..1), disclaimer:string. "
                + "You MUST analyze ALL recent notes and correlate meals, insulin doses, and pauses with glucose movement. "
                + "You MUST use RAG references below to explain the 2h prediction method and correction/pause guidance. "
                + "Prediction must follow the provided prediction-math block exactly; if references conflict, state uncertainty. "
                + "For correction support, provide calculation guidance with formula and explicit safety language (never imperative dosing orders). "
                + "Treat source=real notes as primary evidence and source=mock notes as synthetic/testing context. "
                + "No markdown, no extra text. "
                + "Latest=" + context.getLatestGlucose()
                + ", avg=" + context.getAvgGlucose()
                + ", delta=" + context.getDeltaGlucose()
                + ", activeCOB=" + context.getActiveCob()
                + ", activeIOB=" + context.getActiveIob()
                + ", predicted2h=" + context.getPredictedGlucose2h()
                + ", estimatedCorrectionUnits=" + context.getEstimatedCorrectionUnits()
                + ", avgPreBolusPauseMin=" + context.getAvgPreBolusPauseMinutes()
                + ", latestPreBolusPauseMin=" + context.getLatestPreBolusPauseMinutes()
                + ", notesCount=" + context.getNotes().size()
                + ". Recent notes:\n" + notesBlock
                + "\nPrediction math:\n" + predictionMathBlock
                + "\nReferences:\n" + refs;
    }

    private String buildMarkdownPrompt(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            String followUpQuestion,
            List<AiAnalysisRequest.AiChatTurnDto> conversationTurns
    ) {
        String refs = chunks.stream()
                .map(this::toReferenceLine)
                .collect(Collectors.joining("\n"));
        String notesBlock = formatRecentNotes(context);
        String predictionMathBlock = formatPredictionMath(context);
        boolean hasFollowUp = followUpQuestion != null && !followUpQuestion.isBlank();
        String historyBlock = formatConversationTurns(conversationTurns);
        return "You are a glucose assistant. Write concise markdown with sections: "
                + "## Summary, ## Detected patterns, ## Likely mistakes, ## Recommendations, ## Disclaimer. "
                + "Analyze ALL recent notes and explicitly account for meals, insulin doses, and pauses. "
                + "Use RAG references to explain why the 2h prediction/correction/pause guidance is chosen. "
                + "Prediction must follow the provided prediction-math block exactly. "
                + "Never issue imperative dosing commands; provide cautious decision-support wording. "
                + "Treat source=real notes as primary evidence and source=mock notes as synthetic/testing context. "
                + "Use bullet points where relevant. Do not output JSON. "
                + "Latest=" + context.getLatestGlucose()
                + ", avg=" + context.getAvgGlucose()
                + ", delta=" + context.getDeltaGlucose()
                + ", activeCOB=" + context.getActiveCob()
                + ", activeIOB=" + context.getActiveIob()
                + ", predicted2h=" + context.getPredictedGlucose2h()
                + ", estimatedCorrectionUnits=" + context.getEstimatedCorrectionUnits()
                + ", avgPreBolusPauseMin=" + context.getAvgPreBolusPauseMinutes()
                + ", latestPreBolusPauseMin=" + context.getLatestPreBolusPauseMinutes()
                + ", notesCount=" + context.getNotes().size()
                + ". Recent notes:\n" + notesBlock
                + "\nPrediction math:\n" + predictionMathBlock
                + "\nReferences:\n" + refs
                + (historyBlock.isBlank() ? "" : "\nConversation history:\n" + historyBlock)
                + (hasFollowUp
                ? "\nUser follow-up question:\n" + followUpQuestion + "\nRespond directly to this question while using the same context and references."
                : "\nNo direct user question provided. Provide proactive analysis.");
    }

    private String formatPredictionMath(AnalysisContext context) {
        double carb = context.getActiveCob() == null ? 0.0 : context.getActiveCob();
        double iob = context.getActiveIob() == null ? 0.0 : context.getActiveIob();
        double isf = context.getCobSettings() != null && context.getCobSettings().getIsf() != null
                ? context.getCobSettings().getIsf() : 1.0;
        double carbRatio = context.getCobSettings() != null && context.getCobSettings().getCarbRatio() != null
                ? context.getCobSettings().getCarbRatio() : 2.0;
        double preBolusTiming = context.getPreBolusTimingContribution() == null ? 0.0 : context.getPreBolusTimingContribution();
        return "predicted2h = clamp(1.0..25.0, latest + carbContribution + insulinContribution + preBolusTimingContribution)\n"
                + "carbContribution = (activeCOB/10) * carbRatio = (" + carb + "/10) * " + carbRatio + "\n"
                + "insulinContribution = -(activeIOB * isf) = -(" + iob + " * " + isf + ")\n"
                + "preBolusTimingContribution = " + preBolusTiming;
    }

    private String formatRecentNotes(AnalysisContext context) {
        if (context.getNotes() == null || context.getNotes().isEmpty()) {
            return "- none";
        }
        List<String> lines = new ArrayList<>();
        for (var n : context.getNotes()) {
            List<String> tags = new ArrayList<>();
            if (n.getMeal() != null && !n.getMeal().isBlank() && !"none".equalsIgnoreCase(n.getMeal())) {
                tags.add("meal=" + n.getMeal());
            }
            if (n.getCarbs() != null && n.getCarbs() > 0) {
                tags.add("carbs=" + n.getCarbs() + "g");
            }
            if (n.getInsulin() != null && n.getInsulin() > 0) {
                tags.add("insulin=" + n.getInsulin() + "U");
            }
            if (n.getGlucoseLevel() != null) {
                tags.add("glucose=" + n.getGlucoseLevel() + "mmol/L");
            }
            if (n.getInsulinDose() != null && !n.getInsulinDose().isBlank()) {
                tags.add("insulinDoseDetail=" + n.getInsulinDose());
            }
            String noteText = ((n.getDetailedInput() == null ? "" : n.getDetailedInput()) + " "
                    + (n.getComment() == null ? "" : n.getComment())).trim();
            String lower = noteText.toLowerCase();
            if (lower.contains("pause") || lower.contains("paused")) {
                tags.add("pauseEvent=true");
            }
            if (!noteText.isBlank()) {
                tags.add("text=" + noteText);
            }
            tags.add("source=" + (n.isMockData() ? "mock" : "real"));
            lines.add("- " + formatHumanReadableTimestamp(n.getTimestamp()) + " | " + String.join("; ", tags));
        }
        return String.join("\n", lines);
    }

    private String toReferenceLine(ClinicalKnowledgeChunk chunk) {
        String sourceTitle = chunk.getSourceTitle() == null || chunk.getSourceTitle().isBlank()
                ? chunk.getTitle() : chunk.getSourceTitle();
        String sourceUrl = chunk.getSourceUrl() == null ? "" : chunk.getSourceUrl();
        String sourceName = chunk.getSourceName() == null ? "internal" : chunk.getSourceName();
        String topic = chunk.getSourceTopic() == null ? "general" : chunk.getSourceTopic();
        return "[" + chunk.getConditionTag() + "] "
                + sourceTitle
                + " | source=" + sourceName
                + " | topic=" + topic
                + " | url=" + sourceUrl;
    }

    private String formatConversationTurns(List<AiAnalysisRequest.AiChatTurnDto> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        return turns.stream()
                .filter(t -> t != null && t.getContent() != null && !t.getContent().isBlank())
                .limit(12)
                .map(t -> {
                    String role = t.getRole() == null ? "user" : t.getRole().trim().toLowerCase();
                    String normalizedRole = "assistant".equals(role) ? "assistant" : "user";
                    return "- " + normalizedRole + ": " + t.getContent().trim();
                })
                .collect(Collectors.joining("\n"));
    }

    private String formatHumanReadableTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "unknown time";
        }
        LocalDate today = LocalDate.now();
        LocalDate noteDate = timestamp.toLocalDate();
        if (noteDate.equals(today)) {
            return timestamp.format(TIME_FORMAT);
        }
        if (noteDate.equals(today.minusDays(1))) {
            return "yesterday at " + timestamp.format(TIME_FORMAT);
        }
        return timestamp.format(FULL_DATE_TIME_FORMAT);
    }

    private String buildOllamaPayload(String prompt, boolean stream, boolean jsonFormat) {
        return buildOllamaPayload(prompt, stream, jsonFormat, ollamaModel, ollamaNumCtx);
    }

    private String buildOllamaPayload(String prompt, boolean stream, boolean jsonFormat, String model, int numCtx) {
        var node = objectMapper.createObjectNode()
                .put("model", model)
                .put("prompt", prompt)
                .put("stream", stream);
        node.putObject("options").put("num_ctx", numCtx);
        if (jsonFormat) {
            node.put("format", "json");
        }
        return node.toString();
    }

    private String resolveModel(String modelOverride) {
        if (modelOverride == null || modelOverride.isBlank()) {
            return ollamaModel;
        }
        return modelOverride.trim();
    }

    private int resolveNumCtx(Integer numCtxOverride) {
        if (numCtxOverride == null) {
            return ollamaNumCtx;
        }
        return Math.max(512, Math.min(32768, numCtxOverride));
    }

    private Usage extractUsage(JsonNode node) {
        if (node == null) return new Usage(null, null);
        Integer prompt = node.has("prompt_eval_count") ? node.get("prompt_eval_count").asInt() : null;
        Integer completion = node.has("eval_count") ? node.get("eval_count").asInt() : null;
        return new Usage(prompt, completion);
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "{}";
    }

    private void applyOllamaAuth(HttpHeaders headers) {
        if (ollamaApiKey != null && !ollamaApiKey.isBlank()) {
            headers.setBearerAuth(ollamaApiKey.trim());
        }
    }

    private void applyOllamaAuth(HttpRequest.Builder requestBuilder) {
        if (ollamaApiKey != null && !ollamaApiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + ollamaApiKey.trim());
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class GatewayResult {
        private String modelId;
        private String rawOutput;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer contextWindow;
        private long latencyMs;
    }

    private record Usage(Integer promptTokens, Integer completionTokens) {}
    private record OllamaResponse(String content, Usage usage) {}
}
