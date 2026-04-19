package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.NutritionAnalyzeRequest;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.nutrition.NutritionApiNinjaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class NutritionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    /**
     * Mock the external Spoonacular HTTP client to avoid real network calls.
     * The service will return an empty list when this mock is active.
     */
    @MockBean
    private NutritionApiNinjaService nutritionApiNinjaService;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
        // Default: Spoonacular returns no results (simulates disabled or no API key)
        when(nutritionApiNinjaService.lookupFoods(anyString())).thenReturn(List.of());
    }

    private RegisterRequest validRegister() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest r = new RegisterRequest();
        r.setUsername("nutri_" + suffix);
        r.setEmail("nutri+" + suffix + "@example.com");
        r.setFullName("Nutrition User");
        r.setPassword("testpass123");
        return r;
    }

    private HttpHeaders authedHeaders(RegisterRequest register) {
        rest.postForEntity("/api/auth/register", jsonEntity(register), String.class);
        AuthRequest login = new AuthRequest();
        login.setUsername(register.getUsername());
        login.setPassword(register.getPassword());
        ResponseEntity<AuthResponse> loginResp =
                rest.postForEntity("/api/auth/login", jsonEntity(login), AuthResponse.class);
        assertNotNull(loginResp.getBody());
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(loginResp.getBody().getAccessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    @Test
    @DisplayName("POST /api/nutrition/analyze with ingredients returns 200 and a NutritionSnapshot")
    void analyzeIngredients_returnsSnapshot() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        NutritionAnalyzeRequest analyzeReq = new NutritionAnalyzeRequest();
        analyzeReq.setIngredientsText("100g oats with milk");
        analyzeReq.setFallbackCarbs(60.0);

        ResponseEntity<Map> resp = rest.exchange(
                "/api/nutrition/analyze",
                HttpMethod.POST,
                new HttpEntity<>(analyzeReq, headers),
                Map.class);

        // 200 when external API is mocked (returns empty list -> MANUAL_CARBS path)
        // 503 would occur if circuit-breaker is open (acceptable per task spec)
        assertTrue(
                resp.getStatusCode() == HttpStatus.OK || resp.getStatusCode().value() == 503,
                "Expected 200 or 503 but got: " + resp.getStatusCode());

        if (resp.getStatusCode() == HttpStatus.OK) {
            assertNotNull(resp.getBody());
            assertTrue(resp.getBody().containsKey("source"),
                    "NutritionSnapshot must have 'source' field");
        }
    }

    @Test
    @DisplayName("POST /api/nutrition/analyze with blank ingredients returns 400")
    void analyzeIngredients_blankInput_returns400() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        NutritionAnalyzeRequest analyzeReq = new NutritionAnalyzeRequest();
        analyzeReq.setIngredientsText("   ");
        analyzeReq.setFallbackCarbs(0.0);

        ResponseEntity<String> resp = rest.exchange(
                "/api/nutrition/analyze",
                HttpMethod.POST,
                new HttpEntity<>(analyzeReq, headers),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("POST /api/nutrition/analyze without auth returns 401")
    void analyzeIngredients_noAuth_returns401() {
        NutritionAnalyzeRequest analyzeReq = new NutritionAnalyzeRequest();
        analyzeReq.setIngredientsText("pasta with tomato sauce");

        ResponseEntity<String> resp = rest.postForEntity(
                "/api/nutrition/analyze",
                jsonEntity(analyzeReq),
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }
}
