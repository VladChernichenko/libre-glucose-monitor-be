package che.glucosemonitorbe.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class QwenGatewayService {

    private final ObjectMapper objectMapper;

    @Value("${app.ai.qwen.enabled:false}")
    private boolean qwenEnabled;

    @Value("${app.ai.qwen.url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String qwenBaseUrl;

    @Value("${app.ai.qwen.model:qwen-plus}")
    private String qwenModel;

    @Value("${app.ai.qwen.vision-model:qwen-vl-max}")
    private String qwenVisionModel;

    @Value("${app.ai.qwen.api-key:}")
    private String qwenApiKey;

    @PostConstruct
    void logStartup() {
        log.info("[Qwen] enabled={} apiKeySet={} model={} visionModel={}",
                qwenEnabled, qwenApiKey != null && !qwenApiKey.isBlank(), qwenModel, qwenVisionModel);
    }

    public boolean isAvailable() {
        return qwenEnabled && qwenApiKey != null && !qwenApiKey.isBlank();
    }

    /**
     * Stream markdown from Qwen (DashScope OpenAI-compatible API).
     * Returns accumulated content + token usage.
     */
    public LlmGatewayService.GatewayResult streamMarkdown(
            String prompt,
            String modelHint,
            Consumer<String> tokenConsumer
    ) throws Exception {
        long start = System.currentTimeMillis();
        String model = resolveModel(modelHint);
        String payload = buildChatPayload(prompt, model, true);

        log.info("[Qwen] REQUEST model={} url={}/chat/completions promptChars={}",
                model, qwenBaseUrl, prompt.length());
        log.debug("[Qwen] PROMPT:\n{}", prompt);

        HttpResponse<java.io.InputStream> response = sendRequest(payload);
        log.info("[Qwen] RESPONSE httpStatus={}", response.statusCode());
        StringBuilder fullContent = new StringBuilder();
        Integer promptTokens = null;
        Integer completionTokens = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                JsonNode chunk = objectMapper.readTree(data);

                JsonNode usageNode = chunk.get("usage");
                if (usageNode != null && !usageNode.isNull()) {
                    promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : null;
                    completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : null;
                }

                JsonNode choices = chunk.get("choices");
                if (choices != null && choices.isArray() && !choices.isEmpty()) {
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null && delta.has("content")) {
                        String token = delta.get("content").asText("");
                        if (!token.isEmpty()) {
                            fullContent.append(token);
                            if (tokenConsumer != null) tokenConsumer.accept(token);
                        }
                    }
                }
            }
        }

        String preview = fullContent.length() > 200
                ? fullContent.substring(0, 200) + "…" : fullContent.toString();
        log.info("[Qwen] DONE model={} promptTokens={} completionTokens={} latencyMs={} responsePreview={}",
                model, promptTokens, completionTokens, System.currentTimeMillis() - start, preview);
        log.debug("[Qwen] FULL RESPONSE:\n{}", fullContent);

        return LlmGatewayService.GatewayResult.builder()
                .modelId("qwen:" + model)
                .rawOutput(fullContent.toString())
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .contextWindow(128000)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    /**
     * Analyze a base64-encoded image with qwen-vl (vision model).
     * Returns the model's text response (expected JSON nutrition data).
     */
    public String analyzeImageSync(String base64Image, String mimeType, String prompt) throws Exception {
        String model = qwenVisionModel;
        String contentArray = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("type", "image_url")
                        .set("image_url", objectMapper.createObjectNode()
                                .put("url", "data:" + mimeType + ";base64," + base64Image)))
                .add(objectMapper.createObjectNode()
                        .put("type", "text")
                        .put("text", prompt))
                .toString();

        String payload = objectMapper.createObjectNode()
                .put("model", model)
                .put("stream", false)
                .set("messages", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("role", "user")
                                .set("content", objectMapper.readTree(contentArray))))
                .toString();

        log.info("[QwenVision] REQUEST model={} url={}/chat/completions mimeType={}",
                model, qwenBaseUrl, mimeType);
        HttpResponse<String> response = sendRequestSync(payload);
        log.info("[QwenVision] RESPONSE httpStatus={} bodyChars={}", response.statusCode(), response.body().length());
        log.debug("[QwenVision] RESPONSE BODY:\n{}", response.body());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException("Qwen vision returned no choices");
        }
        String content = choices.get(0).path("message").path("content").asText("{}");
        String contentPreview = content.length() > 300 ? content.substring(0, 300) + "…" : content;
        log.info("[QwenVision] CONTENT preview={}", contentPreview);
        return content;
    }

    private HttpResponse<java.io.InputStream> sendRequest(String payload) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(qwenBaseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + qwenApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            log.error("[Qwen] HTTP error status={}", response.statusCode());
            throw new RuntimeException("Qwen API error status=" + response.statusCode());
        }
        return response;
    }

    private HttpResponse<String> sendRequestSync(String payload) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(qwenBaseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + qwenApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Qwen API error status=" + response.statusCode() + " body=" + response.body());
        }
        return response;
    }

    private String buildChatPayload(String prompt, String model, boolean stream) throws Exception {
        var node = objectMapper.createObjectNode()
                .put("model", model)
                .put("stream", stream);
        if (stream) {
            node.set("stream_options", objectMapper.createObjectNode().put("include_usage", true));
        }
        node.set("messages", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("role", "user")
                        .put("content", prompt)));
        return node.toString();
    }

    private String resolveModel(String hint) {
        if (hint != null && !hint.isBlank() && isQwenModelName(hint)) {
            return hint.trim();
        }
        return qwenModel;
    }

    private boolean isQwenModelName(String name) {
        return name.startsWith("qwen") || name.startsWith("qwen-");
    }
}
