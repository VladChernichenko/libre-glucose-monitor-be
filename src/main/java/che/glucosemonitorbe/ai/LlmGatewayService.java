package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
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

@Service
@RequiredArgsConstructor
public class LlmGatewayService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FULL_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy 'at' HH:mm");

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.ai.ollama.enabled:true}")
    private boolean ollamaEnabled;

    @Value("${app.ai.ollama.url:http://localhost:11434/api/generate}")
    private String ollamaUrl;

    @Value("${app.ai.ollama.model:llama3.1:8b}")
    private String ollamaModel;

    @Value("${app.ai.remote.enabled:false}")
    private boolean remoteEnabled;

    @Value("${app.ai.remote.url:}")
    private String remoteUrl;

    public GatewayResult generate(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) {
        long start = System.currentTimeMillis();

        if (ollamaEnabled) {
            try {
                String response = callOllama(context, chunks);
                return GatewayResult.builder()
                        .modelId("ollama:" + ollamaModel)
                        .rawOutput(response)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception ignored) {
                // fallback
            }
        }

        if (remoteEnabled && remoteUrl != null && !remoteUrl.isBlank()) {
            try {
                String response = callRemote(context, chunks);
                return GatewayResult.builder()
                        .modelId("remote")
                        .rawOutput(response)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception ignored) {
                // fallback
            }
        }

        return GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput("{}")
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
                String response = callOllamaStreaming(context, chunks, tokenConsumer);
                return GatewayResult.builder()
                        .modelId("ollama:" + ollamaModel)
                        .rawOutput(response)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception ignored) {
                // fallback
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
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception ignored) {
                // fallback
            }
        }

        return GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput("{}")
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    public GatewayResult generateStreamingMarkdown(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            Consumer<String> tokenConsumer
    ) {
        long start = System.currentTimeMillis();

        if (ollamaEnabled) {
            try {
                String response = callOllamaStreamingMarkdown(context, chunks, tokenConsumer);
                return GatewayResult.builder()
                        .modelId("ollama:" + ollamaModel)
                        .rawOutput(response)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
            } catch (Exception ignored) {
                // fallback
            }
        }

        return GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput("")
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    private String callOllama(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) throws Exception {
        String prompt = buildJsonPrompt(context, chunks);
        String json = buildOllamaPayload(prompt, false, true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                ollamaUrl,
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class
        );
        JsonNode root = objectMapper.readTree(resp.getBody());
        JsonNode responseNode = root.get("response");
        if (responseNode == null) {
            throw new RestClientException("Ollama response missing `response` field");
        }
        return extractJsonObject(responseNode.asText());
    }

    private String callOllamaStreaming(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            Consumer<String> tokenConsumer
    ) throws Exception {
        String prompt = buildJsonPrompt(context, chunks);
        String payload = buildOllamaPayload(prompt, true, true);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        StringBuilder fullResponse = new StringBuilder();

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
                    break;
                }
            }
        }

        return extractJsonObject(fullResponse.toString());
    }

    private String callOllamaStreamingMarkdown(
            AnalysisContext context,
            List<ClinicalKnowledgeChunk> chunks,
            Consumer<String> tokenConsumer
    ) throws Exception {
        String prompt = buildMarkdownPrompt(context, chunks);
        String payload = buildOllamaPayload(prompt, true, false);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        StringBuilder fullResponse = new StringBuilder();

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
                    break;
                }
            }
        }

        return fullResponse.toString();
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
                .map(c -> "[" + c.getConditionTag() + "] " + c.getContent())
                .collect(Collectors.joining("\n"));
        String notesBlock = formatRecentNotes(context);

        return "You are a glucose assistant. Return ONLY strict JSON object with keys: "
                + "summary:string, detectedPatterns:array[{code,description,severity}], "
                + "likelyMistakes:array[{code,description,severity}], "
                + "recommendations:array[{code,text,priority}], confidence:number(0..1), disclaimer:string. "
                + "You MUST analyze ALL recent notes and correlate meals, insulin doses, and pauses with glucose movement. "
                + "No markdown, no extra text. "
                + "Latest=" + context.getLatestGlucose()
                + ", avg=" + context.getAvgGlucose()
                + ", delta=" + context.getDeltaGlucose()
                + ", notesCount=" + context.getNotes().size()
                + ". Recent notes:\n" + notesBlock
                + "\nReferences:\n" + refs;
    }

    private String buildMarkdownPrompt(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) {
        String refs = chunks.stream()
                .map(c -> "[" + c.getConditionTag() + "] " + c.getContent())
                .collect(Collectors.joining("\n"));
        String notesBlock = formatRecentNotes(context);
        return "You are a glucose assistant. Write concise markdown with sections: "
                + "## Summary, ## Detected patterns, ## Likely mistakes, ## Recommendations, ## Disclaimer. "
                + "Analyze ALL recent notes and explicitly account for meals, insulin doses, and pauses. "
                + "Use bullet points where relevant. Do not output JSON. "
                + "Latest=" + context.getLatestGlucose()
                + ", avg=" + context.getAvgGlucose()
                + ", delta=" + context.getDeltaGlucose()
                + ", notesCount=" + context.getNotes().size()
                + ". Recent notes:\n" + notesBlock
                + "\nReferences:\n" + refs;
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
            lines.add("- " + formatHumanReadableTimestamp(n.getTimestamp()) + " | " + String.join("; ", tags));
        }
        return String.join("\n", lines);
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
        var node = objectMapper.createObjectNode()
                .put("model", ollamaModel)
                .put("prompt", prompt)
                .put("stream", stream);
        if (jsonFormat) {
            node.put("format", "json");
        }
        return node.toString();
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

    @Data
    @Builder
    @AllArgsConstructor
    public static class GatewayResult {
        private String modelId;
        private String rawOutput;
        private long latencyMs;
    }
}
