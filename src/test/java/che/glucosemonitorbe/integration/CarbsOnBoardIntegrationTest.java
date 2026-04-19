package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null", "unchecked"})
class CarbsOnBoardIntegrationTest {

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
        r.setUsername("cob_" + suffix);
        r.setEmail("cob+" + suffix + "@example.com");
        r.setFullName("COB User");
        r.setPassword("testpass123");
        return r;
    }

    /** Register, login, return bearer headers. */
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
    @DisplayName("COB calculate returns featureEnabled and backendMode fields")
    void cobCalculate_returnsExpectedStructure() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        // The controller expects userId as a query param and COBCalculationRequest as body
        Map<String, Object> cobRequest = Map.of(
                "carbs", 60.0,
                "timestamp", LocalDateTime.now().toString()
        );

        String userId = userRepository.findByUsername(req.getUsername())
                .orElseThrow().getId().toString();

        ResponseEntity<Map> resp = rest.exchange(
                "/api/cob/calculate?userId=" + userId,
                HttpMethod.POST,
                new HttpEntity<>(cobRequest, headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("featureEnabled"), "Response must have featureEnabled");
        assertTrue(body.containsKey("backendMode"), "Response must have backendMode");
    }

    @Test
    @DisplayName("COB calculate in backend mode returns data with carbsOnBoard")
    void cobCalculate_backendMode_returnsCobData() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        String userId = userRepository.findByUsername(req.getUsername())
                .orElseThrow().getId().toString();

        Map<String, Object> cobRequest = Map.of(
                "carbs", 45.0,
                "timestamp", LocalDateTime.now().minusMinutes(30).toString()
        );

        ResponseEntity<Map> resp = rest.exchange(
                "/api/cob/calculate?userId=" + userId,
                HttpMethod.POST,
                new HttpEntity<>(cobRequest, headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);

        Boolean backendMode = (Boolean) body.get("backendMode");
        if (Boolean.TRUE.equals(backendMode)) {
            assertTrue(body.containsKey("data"), "Backend response must have 'data' field");
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            assertTrue(data.containsKey("carbsOnBoard"),
                    "COB response data must contain carbsOnBoard");
        }
    }

    @Test
    @DisplayName("COB status endpoint returns valid response structure")
    void cobStatus_returnsValidStructure() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        String userId = userRepository.findByUsername(req.getUsername())
                .orElseThrow().getId().toString();

        ResponseEntity<Map> resp = rest.exchange(
                "/api/cob/status?userId=" + userId,
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }
}
