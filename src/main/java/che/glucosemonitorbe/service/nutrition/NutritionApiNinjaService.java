package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.circuitbreaker.CircuitBreaker;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.config.NutritionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NutritionApiNinjaService {
    private final NutritionProperties nutritionProperties;
    private final CircuitBreakerManager circuitBreakerManager;
    private final RestTemplateBuilder restTemplateBuilder;

    public List<Map<String, Object>> lookupFoods(String query) {
        if (!nutritionProperties.isEnabled() || nutritionProperties.getApiKey() == null || nutritionProperties.getApiKey().isBlank()) {
            return List.of();
        }
        CircuitBreaker breaker = circuitBreakerManager.getCircuitBreaker("nutrition-api-ninjas");
        return breaker.executeWithFallback(
                () -> executeLookup(query),
                List::of
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executeLookup(String query) {
        try {
            RestTemplate restTemplate = restTemplateBuilder
                    .setConnectTimeout(Duration.ofMillis(nutritionProperties.getTimeoutMs()))
                    .setReadTimeout(Duration.ofMillis(nutritionProperties.getTimeoutMs()))
                    .build();
            URI uri = UriComponentsBuilder.fromHttpUrl(nutritionProperties.getBaseUrl())
                    .queryParam("query", query)
                    .build(true)
                    .toUri();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", nutritionProperties.getApiKey());
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<List> response = restTemplate.exchange(uri, HttpMethod.GET, entity, List.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return List.of();
            }
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Object row : response.getBody()) {
                if (row instanceof Map<?, ?> mapRow) {
                    normalized.add((Map<String, Object>) mapRow);
                }
            }
            return normalized;
        } catch (Exception ex) {
            log.warn("Nutrition API lookup failed: {}", ex.getMessage());
            throw ex;
        }
    }
}
