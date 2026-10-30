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
import org.springframework.stereotype.Service;

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

    private final ObjectMapper objectMapper;
    private final QwenGatewayService qwenGatewayService;

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

    /**
     * Assistant chat reply: streams a short answer to {@code question} in {@code language} ("ru" or
     * "en"), grounded in the current context, retrieved passages and the given recent chat turns.
     * Uses the configured provider (Qwen when available, else Ollama) with no model/provider override.
     */
    public GatewayResult streamChatReply(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            String question,
            List<AiAnalysisRequest.AiChatTurnDto> conversationTurns,
            String language,
            Consumer<String> tokenConsumer
    ) {
        long start = System.currentTimeMillis();
        String prompt = buildChatPrompt(context, chunks, question, conversationTurns, language);
        ClientSink sink = new ClientSink(tokenConsumer);
        if (qwenGatewayService.isAvailable()) {
            try {
                return qwenGatewayService.streamMarkdown(prompt, null, sink);
            } catch (Exception e) {
                sink.rethrowIfAborted();
                log.warn("[Qwen] chat reply failed, falling back to Ollama. reason={}", e.getMessage());
            }
        }
        if (ollamaEnabled) {
            try {
                OllamaResponse response = streamOllamaPrompt(prompt, ollamaModel, ollamaNumCtx, sink);
                return GatewayResult.builder()
                        .modelId("ollama:" + ollamaModel)
                        .rawOutput(response.content())
                        .promptTokens(response.usage().promptTokens())
                        .completionTokens(response.usage().completionTokens())
                        .contextWindow(ollamaNumCtx)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception e) {
                sink.rethrowIfAborted();
                log.warn("[Ollama] chat reply failed, falling back. reason={}", e.getMessage());
            }
        }
        String fallback = "ru".equals(language)
                ? "Ассистент сейчас недоступен. Попробуйте ещё раз через минуту."
                : "The assistant is unavailable right now. Please try again in a minute.";
        sink.accept(fallback);
        return GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput(fallback)
                .contextWindow(ollamaNumCtx)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    /**
     * Wraps the chat token consumer so a failure thrown BY the consumer (the client went away) is
     * told apart from a provider failure: it is rethrown instead of triggering the next provider.
     */
    private static final class ClientSink implements Consumer<String> {
        private final Consumer<String> delegate;
        private RuntimeException abort;

        ClientSink(Consumer<String> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void accept(String token) {
            if (delegate == null) return;
            try {
                delegate.accept(token);
            } catch (RuntimeException e) {
                abort = e;
                throw e;
            }
        }

        void rethrowIfAborted() {
            if (abort != null) throw abort;
        }
    }

    private String buildChatPrompt(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            String question,
            List<AiAnalysisRequest.AiChatTurnDto> conversationTurns,
            String language
    ) {
        String languageName = "ru".equals(language) ? "Russian" : "English";
        String refs = chunks.stream().map(this::toReferenceLine).collect(Collectors.joining("\n"));
        String historyBlock = formatConversationTurns(conversationTurns);
        return "You are a personal type 1 diabetes assistant chatting with the user. "
                + "Reply in " + languageName + ", the language of the user's message, even though the facts below are in English. "
                + "Be brief: a few sentences, markdown allowed. "
                + "Quote numbers only from the facts below; never invent numbers. "
                + "Never issue imperative dosing commands; provide cautious decision-support wording. "
                + "Facts: latestGlucose=" + context.getLatestGlucose()
                + ", delta=" + context.getDeltaGlucose()
                + ", activeCOB=" + context.getActiveCob()
                + ", activeIOB=" + context.getActiveIob()
                + ", predicted2h=" + context.getPredictedGlucose2h()
                + ". Recent notes:\n" + formatRecentNotes(context)
                + "\nReferences:\n" + (refs.isBlank() ? "- none" : refs)
                + (historyBlock.isBlank() ? "" : "\nConversation history (oldest first):\n" + historyBlock)
                + "\nUser message:\n" + question;
    }

    /** Streams a free-text (non-JSON) completion for {@code prompt} from Ollama. */
    private OllamaResponse streamOllamaPrompt(
            String prompt, String model, int numCtx, Consumer<String> tokenConsumer) throws Exception {
        String payload = buildOllamaPayload(prompt, true, false, model, numCtx);

        log.debug("[Ollama] model={} url={} numCtx={} auth={}", model, ollamaUrl, numCtx,
                (ollamaApiKey != null && !ollamaApiKey.isBlank()) ? "Bearer ***" : "none");
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

    private Usage extractUsage(JsonNode node) {
        if (node == null) return new Usage(null, null);
        Integer prompt = node.has("prompt_eval_count") ? node.get("prompt_eval_count").asInt() : null;
        Integer completion = node.has("eval_count") ? node.get("eval_count").asInt() : null;
        return new Usage(prompt, completion);
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
