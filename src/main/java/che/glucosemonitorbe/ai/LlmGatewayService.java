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

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LlmGatewayService {

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

    private String callOllama(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) throws Exception {
        String prompt = buildPrompt(context, chunks);
        String json = objectMapper.createObjectNode()
                .put("model", ollamaModel)
                .put("prompt", prompt)
                .put("format", "json")
                .put("stream", false)
                .toString();

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

    private String callRemote(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = objectMapper.createObjectNode()
                .put("context", buildPrompt(context, chunks))
                .toString();
        ResponseEntity<String> resp = restTemplate.exchange(
                remoteUrl,
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                String.class
        );
        return resp.getBody() == null ? "{}" : resp.getBody();
    }

    private String buildPrompt(AnalysisContext context, List<ClinicalKnowledgeChunk> chunks) {
        String refs = chunks.stream()
                .map(c -> "[" + c.getConditionTag() + "] " + c.getContent())
                .collect(Collectors.joining("\n"));

        return "You are a glucose assistant. Return ONLY strict JSON object with keys: "
                + "summary:string, detectedPatterns:array[{code,description,severity}], "
                + "likelyMistakes:array[{code,description,severity}], "
                + "recommendations:array[{code,text,priority}], confidence:number(0..1), disclaimer:string. "
                + "No markdown, no extra text. "
                + "Latest=" + context.getLatestGlucose()
                + ", avg=" + context.getAvgGlucose()
                + ", delta=" + context.getDeltaGlucose()
                + ", notesCount=" + context.getNotes().size()
                + ". References:\n" + refs;
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
