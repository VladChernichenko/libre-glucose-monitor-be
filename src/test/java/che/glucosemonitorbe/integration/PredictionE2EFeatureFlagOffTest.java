package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: {@code POST /api/glucose-calculations/} when backend feature flags are off.
 *
 * <p>Kept separate from {@link PredictionE2ETest} because {@code @TestPropertySource} on a
 * {@code @Nested} inner class does not override the parent {@link SpringBootTest} context
 * (parent enables all flags at 100%).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "app.features.backend-mode-enabled=false",
        "app.features.glucose-calculations-enabled=false"
})
@SuppressWarnings({"resource", "null"})
class PredictionE2EFeatureFlagOffTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private LocalDateTime testNow;
    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        testNow = LocalDateTime.now();
        authHeaders = registerAndLogin();
    }

    @Test
    @DisplayName("backendMode=false ? data field absent, featureEnabled present")
    void featureFlagOff_shortCircuitsWithoutCallingService() throws Exception {
        ResponseEntity<String> resp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                new HttpEntity<>(calcBody(6.5), authHeaders),
                String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode root = mapper.readTree(resp.getBody());

        assertTrue(root.has("featureEnabled"),
                "Response must contain featureEnabled");
        assertFalse(root.path("backendMode").asBoolean(),
                "backendMode must be false when feature flag is disabled");
        assertFalse(root.has("data") && !root.path("data").isNull(),
                "data field must not be populated when backendMode=false");
    }

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("pred_off_" + suffix);
        reg.setEmail("pred_off+" + suffix + "@example.com");
        reg.setFullName("Pred Off User");
        reg.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", jsonEntity(reg), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        ResponseEntity<AuthResponse> resp =
                rest.postForEntity("/api/auth/login", jsonEntity(login), AuthResponse.class);
        assertNotNull(resp.getBody(), "Login response body must not be null");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(resp.getBody().getAccessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private Map<String, Object> calcBody(double currentGlucose) {
        return Map.of(
                "currentGlucose", currentGlucose,
                "clientTimeInfo", Map.of(
                        "timestamp", testNow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        "timezone", "UTC",
                        "locale", "en-US",
                        "timezoneOffset", 0
                ),
                "includePredictionFactors", true
        );
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
