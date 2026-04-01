package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.circuitbreaker.CircuitBreaker;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.config.NutritionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("null")
class NutritionApiNinjaServiceIntegrationTest {

    @Mock(answer = Answers.RETURNS_SELF)
    private RestTemplateBuilder restTemplateBuilder;

    @Mock
    private CircuitBreakerManager circuitBreakerManager;

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private NutritionProperties properties;
    private NutritionApiNinjaService service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        properties = new NutritionProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://api.api-ninjas.com/v1/nutrition");
        properties.setTimeoutMs(2000);

        CircuitBreaker breaker = new CircuitBreaker("nutrition-api-ninjas", 5, 1000, 2);
        when(circuitBreakerManager.getCircuitBreaker("nutrition-api-ninjas")).thenReturn(breaker);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);

        service = new NutritionApiNinjaService(properties, circuitBreakerManager, restTemplateBuilder);
    }

    @Test
    void lookupFoodsShouldCallApiAndNormalizeList() {
        server.expect(once(), requestTo("https://api.api-ninjas.com/v1/nutrition?query=apple"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Api-Key", "test-key"))
                .andRespond(withSuccess("[{\"name\":\"apple\",\"carbohydrates_total_g\":14.0}]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = service.lookupFoods("apple");

        assertEquals(1, result.size());
        assertEquals("apple", result.get(0).get("name"));
        server.verify();
    }

    @Test
    void lookupFoodsReturnsEmptyWhenDisabled() {
        properties.setEnabled(false);

        List<Map<String, Object>> result = service.lookupFoods("banana");

        assertTrue(result.isEmpty());
    }
}
