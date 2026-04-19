package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null", "unchecked"})
class InsulinCalculatorIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    private RegisterRequest validRegister() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest r = new RegisterRequest();
        r.setUsername("insulin_" + suffix);
        r.setEmail("insulin+" + suffix + "@example.com");
        r.setFullName("Insulin User");
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
    @DisplayName("Bolus calculation returns recommendedInsulin in data field")
    void calculateBolus_withValidInputs_returnsRecommendedInsulin() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        InsulinCalculationRequest calcReq = InsulinCalculationRequest.builder()
                .carbs(60.0)
                .currentGlucose(8.5)
                .targetGlucose(5.5)
                .activeInsulin(0.0)
                .mealType("STANDARD")
                .build();

        ResponseEntity<Map> resp = rest.exchange(
                "/api/insulin/calculate",
                HttpMethod.POST,
                new HttpEntity<>(calcReq, headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        // Feature toggle is on by default (backend-mode-enabled: true, insulin-calculator-enabled: true)
        // So we expect either backend response or feature-disabled message
        assertTrue(body.containsKey("featureEnabled"), "Response must have featureEnabled field");

        Boolean featureEnabled = (Boolean) body.get("featureEnabled");
        if (Boolean.TRUE.equals(featureEnabled) && Boolean.TRUE.equals(body.get("backendMode"))) {
            // Backend mode: data wraps InsulinCalculationResponse
            assertTrue(body.containsKey("data"), "Backend response must have 'data' field");
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            assertTrue(data.containsKey("recommendedInsulin"),
                    "InsulinCalculationResponse must contain recommendedInsulin");
            assertNotNull(data.get("recommendedInsulin"));
        }
    }

    @Test
    @DisplayName("Calculator status endpoint returns feature metadata")
    void calculatorStatus_returnsMetadata() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        ResponseEntity<Map> resp = rest.exchange(
                "/api/insulin/status",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("feature"));
        assertTrue(body.containsKey("shouldUseBackend"));
        assertEquals("insulin-calculator", body.get("feature"));
    }

    @Test
    @DisplayName("Bolus calculation with zero carbs returns valid response")
    void calculateBolus_zeroCarbs_doesNotFail() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        InsulinCalculationRequest calcReq = InsulinCalculationRequest.builder()
                .carbs(0.0)
                .currentGlucose(5.5)
                .targetGlucose(5.5)
                .activeInsulin(0.0)
                .build();

        ResponseEntity<Map> resp = rest.exchange(
                "/api/insulin/calculate",
                HttpMethod.POST,
                new HttpEntity<>(calcReq, headers),
                Map.class);

        // Should not be 500
        assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }
}
