package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.circuitbreaker.CircuitBreaker;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.config.NutritionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NutritionApiNinjaService {
    private final NutritionProperties nutritionProperties;
    private final CircuitBreakerManager circuitBreakerManager;
    private final RestTemplateBuilder restTemplateBuilder;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> lookupFoods(String query) {
        if (!nutritionProperties.isEnabled()
                || nutritionProperties.getApiKey() == null
                || nutritionProperties.getApiKey().isBlank()
                || query == null
                || query.isBlank()) {
            log.debug(
                    "Spoonacular lookup skipped: enabled={} hasApiKey={} blankQuery={}",
                    nutritionProperties.isEnabled(),
                    nutritionProperties.getApiKey() != null && !nutritionProperties.getApiKey().isBlank(),
                    query == null || query.isBlank());
            return List.of();
        }
        CircuitBreaker breaker = circuitBreakerManager.getCircuitBreaker("nutrition-spoonacular");
        return breaker.executeWithFallback(
                () -> executeLookup(query),
                List::of
        );
    }

    private List<Map<String, Object>> executeLookup(String query) {
        try {
            RestTemplate restTemplate = restTemplateBuilder.connectTimeout(
                    Duration.ofMillis(nutritionProperties.getTimeoutMs()))
                    .readTimeout(Duration.ofMillis(nutritionProperties.getTimeoutMs()))
                    .build();
            URI searchUri = UriComponentsBuilder.fromUriString(nutritionProperties.getBaseUrl())
                    .path("/food/ingredients/search")
                    .queryParam("query", query)
                    .queryParam("number", 5)
                    .queryParam("apiKey", nutritionProperties.getApiKey())
                    .build()
                    .toUri();

            log.debug("Spoonacular ingredient search queryLength={}", query.length());
            ResponseEntity<String> searchResponse = restTemplate.exchange(searchUri, HttpMethod.GET, null, String.class);
            if (!searchResponse.getStatusCode().is2xxSuccessful() || searchResponse.getBody() == null) {
                log.warn(
                        "Spoonacular search failed: status={} bodyPresent={}",
                        searchResponse.getStatusCode(),
                        searchResponse.getBody() != null);
                return List.of();
            }

            JsonNode root = objectMapper.readTree(searchResponse.getBody());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.info("Spoonacular search returned no results queryLength={}", query.length());
                return List.of();
            }

            List<Map<String, Object>> normalized = new ArrayList<>();
            for (JsonNode row : results) {
                Integer id = row.hasNonNull("id") ? row.get("id").asInt() : null;
                String name = row.path("name").asText("");
                if (id == null || name.isBlank()) {
                    continue;
                }
                Map<String, Object> nutritionRow = fetchIngredientNutrition(restTemplate, id, name);
                if (!nutritionRow.isEmpty()) normalized.add(nutritionRow);
            }
            log.info(
                    "Spoonacular lookup ok: searchHits={} normalizedRows={} queryLength={}",
                    results.size(),
                    normalized.size(),
                    query.length());
            return normalized;
        } catch (Exception ex) {
            log.warn("Spoonacular nutrition lookup failed: {}", ex.toString());
            log.debug("Spoonacular nutrition lookup stack trace", ex);
            throw new RuntimeException(ex);
        }
    }

    private Map<String, Object> fetchIngredientNutrition(RestTemplate restTemplate, int ingredientId, String ingredientName) {
        try {
            URI infoUri = UriComponentsBuilder.fromHttpUrl(nutritionProperties.getBaseUrl())
                    .path("/food/ingredients/{id}/information")
                    .queryParam("amount", 100)
                    .queryParam("unit", "g")
                    .queryParam("apiKey", nutritionProperties.getApiKey())
                    .buildAndExpand(ingredientId)
                    .toUri();

            ResponseEntity<String> infoResponse = restTemplate.exchange(infoUri, HttpMethod.GET, null, String.class);
            if (!infoResponse.getStatusCode().is2xxSuccessful() || infoResponse.getBody() == null) {
                return Map.of();
            }

            JsonNode root = objectMapper.readTree(infoResponse.getBody());
            JsonNode nutrients = root.path("nutrition").path("nutrients");
            if (!nutrients.isArray()) return Map.of();

            double carbs = extractNutrientValue(nutrients, "Carbohydrates");
            double fiber = extractNutrientValue(nutrients, "Fiber");
            double protein = extractNutrientValue(nutrients, "Protein");
            double fat = extractNutrientValue(nutrients, "Fat");

            Map<String, Object> normalized = new HashMap<>();
            normalized.put("name", ingredientName);
            normalized.put("carbohydrates_total_g", carbs);
            normalized.put("fiber_g", fiber);
            normalized.put("protein_g", protein);
            normalized.put("fat_total_g", fat);
            return normalized;
        } catch (Exception ex) {
            log.debug("Skipping Spoonacular ingredient {} due to lookup error: {}", ingredientId, ex.getMessage());
            return Map.of();
        }
    }

    private double extractNutrientValue(JsonNode nutrients, String nutrientName) {
        for (JsonNode nutrient : nutrients) {
            if (nutrientName.equalsIgnoreCase(nutrient.path("name").asText())) {
                return nutrient.path("amount").asDouble(0.0);
            }
        }
        return 0.0;
    }
}
