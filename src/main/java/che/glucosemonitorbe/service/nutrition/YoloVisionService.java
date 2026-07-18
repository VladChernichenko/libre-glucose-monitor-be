package che.glucosemonitorbe.service.nutrition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Calls the local Python YOLO microservice (yolo-service/main.py) for food detection.
 * Zero external API cost - runs entirely on-device.
 *
 * Start the service before using:
 *   cd yolo-service && uvicorn main:app --host 0.0.0.0 --port 8001
 */
@Slf4j
@Service
public class YoloVisionService {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${app.ai.yolo.url:http://localhost:8001}")
    private String yoloServiceUrl;

    @Value("${app.ai.yolo.confidence-threshold:0.35}")
    private double confidenceThreshold;

    public YoloVisionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate();
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);   // fail fast if service not running
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }

    /** Returns true if the Python service is up and responding. */
    public boolean isAvailable() {
        try {
            restTemplate.getForObject(yoloServiceUrl + "/health", String.class);
            return true;
        } catch (Exception e) {
            log.debug("[YOLO] Service not available at {}: {}", yoloServiceUrl, e.getMessage());
            return false;
        }
    }

    /**
     * Analyze a meal photo using the local YOLO model.
     *
     * @return NutritionSnapshot on success, or {@code null} if YOLO detected nothing
     *         or the service is unavailable (caller should fall back).
     */
    public NutritionSnapshot analyzeImage(MultipartFile photo) {
        try {
            byte[] bytes = photo.getBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mimeType = photo.getContentType() != null ? photo.getContentType() : "image/jpeg";

            String payload = objectMapper.createObjectNode()
                    .put("image", base64)
                    .put("mime_type", mimeType)
                    .put("confidence_threshold", confidenceThreshold)
                    .toString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            log.info("[YOLO] Sending image to local service imageBytes={} threshold={}",
                    bytes.length, confidenceThreshold);

            ResponseEntity<String> response = restTemplate.exchange(
                    yoloServiceUrl + "/analyze",
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    String.class);

            return parseResponse(objectMapper.readTree(response.getBody()));

        } catch (ResourceAccessException e) {
            log.debug("[YOLO] Service unreachable - skipping YOLO path");
            return null;
        } catch (Exception e) {
            log.warn("[YOLO] Analysis failed: {}", e.getMessage());
            return null;
        }
    }

    private NutritionSnapshot parseResponse(JsonNode root) {
        List<String> foods = new ArrayList<>();
        if (root.has("detected_foods") && root.get("detected_foods").isArray()) {
            root.get("detected_foods").forEach(f -> foods.add(f.asText()));
        }

        if (foods.isEmpty()) {
            log.info("[YOLO] No foods detected above confidence threshold - falling back");
            return null;
        }

        JsonNode n = root.path("nutrition");
        double carbs    = n.path("totalCarbs").asDouble(0);
        double fiber    = n.path("fiber").asDouble(0);
        double protein  = n.path("protein").asDouble(0);
        double fat      = n.path("fat").asDouble(0);
        double gi       = n.path("estimatedGi").asDouble(0);
        double gl       = n.path("glycemicLoad").asDouble(0);
        String speed    = n.path("absorptionSpeedClass").asText("DEFAULT");

        log.info("[YOLO] Detection success foods={} totalCarbs={} gi={} gl={} absorption={}",
                foods, round1(carbs), round1(gi), round1(gl), speed);

        return NutritionSnapshot.builder()
                .absorptionMode(gi > 0 ? "GI_GL_ENHANCED" : "DEFAULT_DECAY")
                .source("YOLO_LOCAL")
                .confidence(0.70)
                .totalCarbs(round1(carbs))
                .fiber(round1(fiber))
                .protein(round1(protein))
                .fat(round1(fat))
                .estimatedGi(gi > 0 ? round1(gi) : null)
                .glycemicLoad(gl > 0 ? round1(gl) : null)
                .absorptionSpeedClass(speed)
                .normalizedFoods(foods)
                .build();
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
