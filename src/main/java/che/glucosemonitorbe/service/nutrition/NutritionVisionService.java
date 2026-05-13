package che.glucosemonitorbe.service.nutrition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NutritionVisionService {

    private static final String VISION_PROMPT =
            "You are a nutrition assistant. Look at this meal photo and identify all visible food items. "
            + "Return ONLY a JSON object with these exact keys: "
            + "foods (array of food name strings), "
            + "totalCarbs (number, grams), "
            + "fiber (number, grams), "
            + "protein (number, grams), "
            + "fat (number, grams), "
            + "estimatedGi (number 0-100), "
            + "glycemicLoad (number), "
            + "absorptionSpeedClass (one of: FAST, MEDIUM, SLOW, DEFAULT). "
            + "Base estimates on typical serving sizes visible. No markdown, no extra text.";

    private final ObjectMapper objectMapper;
    private final NutritionEnrichmentService nutritionEnrichmentService;
    private final RestTemplate restTemplate = buildRestTemplate();

    @Value("${app.ai.ollama.enabled:true}")
    private boolean ollamaEnabled;

    @Value("${app.ai.ollama.url:http://localhost:11434/api/generate}")
    private String ollamaUrl;

    @Value("${app.ai.ollama.vision-model:llava:latest}")
    private String visionModel;

    @Value("${app.ai.ollama.api-key:}")
    private String ollamaApiKey;

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(300_000);
        return new RestTemplate(factory);
    }

    public NutritionSnapshot analyzeImage(MultipartFile photo) {
        try {
            byte[] bytes = photo.getBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return callVisionLlm(base64);
        } catch (Exception e) {
            log.warn("Vision LLM analysis failed, falling back to empty snapshot. reason={}", e.getMessage());
            return NutritionSnapshot.builder()
                    .absorptionMode("DEFAULT_DECAY")
                    .source("VISION_FALLBACK")
                    .confidence(0.1)
                    .totalCarbs(0.0)
                    .fiber(0.0)
                    .protein(0.0)
                    .fat(0.0)
                    .absorptionSpeedClass("DEFAULT")
                    .normalizedFoods(List.of())
                    .build();
        }
    }

    private NutritionSnapshot callVisionLlm(String base64Image) throws Exception {
        if (!ollamaEnabled) {
            throw new IllegalStateException("Ollama is disabled");
        }

        var imagesArray = objectMapper.createArrayNode().add(base64Image);
        String payload = objectMapper.createObjectNode()
                .put("model", visionModel)
                .put("prompt", VISION_PROMPT)
                .put("stream", false)
                .put("format", "json")
                .set("images", imagesArray)
                .toString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ollamaApiKey != null && !ollamaApiKey.isBlank()) {
            headers.setBearerAuth(ollamaApiKey.trim());
        }

        log.debug("[VisionLLM] model={} url={}", visionModel, ollamaUrl);
        ResponseEntity<String> resp = restTemplate.exchange(
                ollamaUrl, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);

        JsonNode root = objectMapper.readTree(resp.getBody());
        String responseText = root.has("response") ? root.get("response").asText("{}") : "{}";
        log.debug("[VisionLLM] raw response: {}", responseText);

        return parseVisionResponse(responseText);
    }

    private NutritionSnapshot parseVisionResponse(String json) {
        try {
            String trimmed = extractJson(json);
            JsonNode node = objectMapper.readTree(trimmed);

            List<String> foods = new ArrayList<>();
            if (node.has("foods") && node.get("foods").isArray()) {
                node.get("foods").forEach(f -> foods.add(f.asText().toLowerCase()));
            }

            double carbs = getDouble(node, "totalCarbs");
            double fiber = getDouble(node, "fiber");
            double protein = getDouble(node, "protein");
            double fat = getDouble(node, "fat");
            double gi = getDouble(node, "estimatedGi");
            double gl = getDouble(node, "glycemicLoad");
            String speed = node.has("absorptionSpeedClass") ? node.get("absorptionSpeedClass").asText("DEFAULT") : "DEFAULT";

            // If LLM identified foods but no carbs, fall back to text enrichment
            if (carbs <= 0 && !foods.isEmpty()) {
                log.info("[VisionLLM] No carbs in response, enriching from food names: {}", foods);
                return nutritionEnrichmentService.enrichFromText(String.join(", ", foods), "", null);
            }

            return NutritionSnapshot.builder()
                    .absorptionMode(gi > 0 ? "GI_GL_ENHANCED" : "DEFAULT_DECAY")
                    .source("VISION_LLM")
                    .confidence(0.75)
                    .totalCarbs(round1(carbs))
                    .fiber(round1(fiber))
                    .protein(round1(protein))
                    .fat(round1(fat))
                    .estimatedGi(gi > 0 ? round1(gi) : null)
                    .glycemicLoad(gl > 0 ? round1(gl) : null)
                    .absorptionSpeedClass(speed)
                    .normalizedFoods(foods)
                    .build();
        } catch (Exception e) {
            log.warn("[VisionLLM] Failed to parse response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse vision LLM response", e);
        }
    }

    private double getDouble(JsonNode node, String key) {
        if (!node.has(key)) return 0.0;
        JsonNode v = node.get(key);
        return v.isNumber() ? v.asDouble() : 0.0;
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        String t = raw.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        return (start >= 0 && end > start) ? t.substring(start, end + 1) : "{}";
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
