package che.glucosemonitorbe.service.nutrition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * LogMeal food recognition API.
 * Step 1: POST image → /v2/image/segmentation/complete  → imageId + food names
 * Step 2: POST imageId → /v2/nutrition/recipe/nutritionalInfo → macro/micro nutrients
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogMealService {

    private final ObjectMapper objectMapper;
    private final NutritionEnrichmentService nutritionEnrichmentService;
    private final RestTemplate restTemplate = buildRestTemplate();

    @Value("${app.logmeal.enabled:false}")
    private boolean enabled;

    @Value("${app.logmeal.api-key:}")
    private String apiKey;

    @Value("${app.logmeal.base-url:https://api.logmeal.com/v2}")
    private String baseUrl;

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public NutritionSnapshot analyzeImage(MultipartFile photo) {
        try {
            String imageId = uploadImage(photo);
            return fetchNutrition(imageId);
        } catch (Exception e) {
            log.warn("[LogMeal] Analysis failed: {}", e.getMessage());
            throw new RuntimeException("LogMeal analysis failed: " + e.getMessage(), e);
        }
    }

    private String uploadImage(MultipartFile photo) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(apiKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(photo.getBytes()) {
            @Override public String getFilename() { return "meal.jpg"; }
        });

        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/image/segmentation/complete",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        JsonNode root = objectMapper.readTree(resp.getBody());
        log.debug("[LogMeal] segmentation response: {}", resp.getBody());

        if (!root.has("imageId")) {
            throw new IllegalStateException("LogMeal response missing imageId: " + resp.getBody());
        }
        return root.get("imageId").asText();
    }

    private NutritionSnapshot fetchNutrition(String imageId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String requestBody = objectMapper.createObjectNode()
                .put("imageId", imageId)
                .toString();

        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/nutrition/recipe/nutritionalInfo",
                HttpMethod.POST,
                new HttpEntity<>(requestBody, headers),
                String.class
        );

        log.info("[LogMeal] nutrition response: {}", resp.getBody());
        return parseNutritionResponse(objectMapper.readTree(resp.getBody()));
    }

    private NutritionSnapshot parseNutritionResponse(JsonNode root) {
        JsonNode info = root.has("nutritional_info") ? root.get("nutritional_info") : root;

        List<String> foods = new ArrayList<>();
        if (root.has("foodName") && root.get("foodName").isArray()) {
            root.get("foodName").forEach(f -> foods.add(f.asText().toLowerCase()));
        }

        // LogMeal v2: nutrients live in nutritional_info.totalNutrients with USDA codes
        JsonNode tn = (info != null && info.has("totalNutrients")) ? info.get("totalNutrients") : null;

        double carbs    = nutrientQuantity(tn, "CHOCDF");
        double fiber    = nutrientQuantity(tn, "FIBTG");
        double protein  = nutrientQuantity(tn, "PROCNT");
        double fat      = nutrientQuantity(tn, "FAT");
        double calories = (info != null && info.has("calories") && info.get("calories").isNumber())
                ? info.get("calories").asDouble() : nutrientQuantity(tn, "ENERC_KCAL");

        log.info("[LogMeal] parsed — foods={} carbs={} fiber={} protein={} fat={} kcal={}",
                foods, carbs, fiber, protein, fat, calories);

        // Use Spoonacular only for GI/GL — always keep LogMeal's own macro values
        if (!foods.isEmpty()) {
            NutritionSnapshot enriched = nutritionEnrichmentService.enrichFromText(
                    String.join(", ", foods), "", carbs > 0 ? carbs : null);
            return NutritionSnapshot.builder()
                    .absorptionMode(enriched.getAbsorptionMode())
                    .source("LOGMEAL")
                    .confidence(0.9)
                    .totalCarbs(round1(carbs))
                    .fiber(round1(fiber))
                    .protein(round1(protein))
                    .fat(round1(fat))
                    .estimatedGi(enriched.getEstimatedGi())
                    .glycemicLoad(enriched.getGlycemicLoad())
                    .absorptionSpeedClass(enriched.getAbsorptionSpeedClass())
                    .normalizedFoods(foods)
                    .build();
        }

        return NutritionSnapshot.builder()
                .absorptionMode("DEFAULT_DECAY")
                .source("LOGMEAL")
                .confidence(0.5)
                .totalCarbs(round1(carbs))
                .fiber(round1(fiber))
                .protein(round1(protein))
                .fat(round1(fat))
                .absorptionSpeedClass("DEFAULT")
                .normalizedFoods(List.of())
                .build();
    }

    /** Read quantity from a LogMeal totalNutrients entry: {"quantity": 10.0, "unit": "g", "label": "..."} */
    private double nutrientQuantity(JsonNode totalNutrients, String code) {
        if (totalNutrients == null || !totalNutrients.has(code)) return 0.0;
        JsonNode entry = totalNutrients.get(code);
        if (entry.has("quantity") && entry.get("quantity").isNumber()) return entry.get("quantity").asDouble();
        return 0.0;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(30_000);
        return new RestTemplate(f);
    }
}
