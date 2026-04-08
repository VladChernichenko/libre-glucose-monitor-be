package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.circuitbreaker.CircuitBreaker;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.config.NutritionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        properties = new NutritionProperties();
        properties.setEnabled(true);
        properties.setApiKey("test_api_key");
        properties.setBaseUrl("https://api.spoonacular.com");
        properties.setTimeoutMs(2000);
        objectMapper = new ObjectMapper();

        CircuitBreaker breaker = new CircuitBreaker("nutrition-spoonacular", 5, 1000, 2);
        when(circuitBreakerManager.getCircuitBreaker("nutrition-spoonacular")).thenReturn(breaker);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);

        service = new NutritionApiNinjaService(properties, circuitBreakerManager, restTemplateBuilder, objectMapper);
    }

    @Test
    void lookupFoodsShouldCallApiAndNormalizeList() {
        server.expect(once(), requestTo("https://api.spoonacular.com/food/ingredients/search?query=apple&number=5&apiKey=test_api_key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"results\":[{\"id\":9003,\"name\":\"apple\"}]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.spoonacular.com/food/ingredients/9003/information?amount=100&unit=g&apiKey=test_api_key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"nutrition\":{\"nutrients\":[{\"name\":\"Carbohydrates\",\"amount\":14.0},{\"name\":\"Fiber\",\"amount\":2.4},{\"name\":\"Protein\",\"amount\":0.3},{\"name\":\"Fat\",\"amount\":0.2}]}}", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = service.lookupFoods("apple");

        assertEquals(1, result.size());
        assertEquals("apple", result.get(0).get("name"));
        assertEquals(14.0, result.get(0).get("carbohydrates_total_g"));
        server.verify();
    }

    @Test
    void lookupFoodsEncodesSpacesInQueryParam() {
        server.expect(once(), requestTo("https://api.spoonacular.com/food/ingredients/search?query=50g%20rye%20bread&number=5&apiKey=test_api_key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"results\":[{\"id\":1,\"name\":\"rye bread\"}]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.spoonacular.com/food/ingredients/1/information?amount=100&unit=g&apiKey=test_api_key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"nutrition\":{\"nutrients\":[{\"name\":\"Carbohydrates\",\"amount\":48.0},{\"name\":\"Fiber\",\"amount\":5.0},{\"name\":\"Protein\",\"amount\":9.0},{\"name\":\"Fat\",\"amount\":3.0}]}}", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = service.lookupFoods("50g rye bread");

        assertEquals(1, result.size());
        assertEquals("rye bread", result.get(0).get("name"));
        server.verify();
    }

    @Test
    void lookupFoodsReturnsEmptyWhenDisabled() {
        properties.setEnabled(false);

        List<Map<String, Object>> result = service.lookupFoods("banana");

        assertTrue(result.isEmpty());
    }
}
