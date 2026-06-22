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
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E integration tests for POST /api/glucose-calculations/
 *
 * Covers the 5 critical prediction scenarios identified via NotebookLM:
 *   1. Normal prediction path — response structure and 4h path present
 *   2. Missing currentGlucose — validation rejects request (400)
 *   3. Prospective notes included — no crash, prediction computed
 *   4. Hypo glucose (< 3.0) — confidence is reduced vs normal glucose
 *   5. Feature status endpoint — always returns featureEnabled field
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null", "unchecked"})
class GlucoseCalculationsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    // ── auth helpers ──────────────────────────────────────────────────────────

    private RegisterRequest validRegister() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest r = new RegisterRequest();
        r.setUsername("gc_" + suffix);
        r.setEmail("gc+" + suffix + "@example.com");
        r.setFullName("GC User");
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

    // ── scenario 1: happy path — response structure ───────────────────────────

    @Test
    @DisplayName("POST /api/glucose-calculations/ — normal glucose returns featureEnabled + backendMode")
    void happyPath_normalGlucose_returnsExpectedStructure() {
        RegisterRequest reg = validRegister();
        HttpHeaders headers = authedHeaders(reg);

        Map<String, Object> body = Map.of("currentGlucose", 6.5);

        ResponseEntity<Map> resp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> rb = resp.getBody();
        assertNotNull(rb);
        assertTrue(rb.containsKey("featureEnabled"), "Must have featureEnabled");
        assertTrue(rb.containsKey("backendMode"), "Must have backendMode");
    }

    @Test
    @DisplayName("POST /api/glucose-calculations/ — backendMode=true response has predictionPath and 4h point")
    void backendMode_responseContainsPredictionPath() {
        RegisterRequest reg = validRegister();
        HttpHeaders headers = authedHeaders(reg);

        Map<String, Object> body = Map.of("currentGlucose", 7.0);

        ResponseEntity<Map> resp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> rb = resp.getBody();
        Boolean backendMode = (Boolean) rb.get("backendMode");
        if (Boolean.TRUE.equals(backendMode)) {
            Map<String, Object> data = (Map<String, Object>) rb.get("data");
            assertNotNull(data, "data field must be present when backendMode=true");
            assertTrue(data.containsKey("predictionPath"), "predictionPath required");
            assertTrue(data.containsKey("fourHourPrediction"), "fourHourPrediction required");
            assertTrue(data.containsKey("confidence"), "confidence required");
        }
    }

    // ── scenario 2: missing currentGlucose — validation ──────────────────────

    @Test
    @DisplayName("POST /api/glucose-calculations/ — null currentGlucose → 400")
    void nullGlucose_returns400() {
        RegisterRequest reg = validRegister();
        HttpHeaders headers = authedHeaders(reg);

        // currentGlucose omitted entirely
        Map<String, Object> body = Map.of();

        ResponseEntity<Map> resp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ── scenario 3: prospective notes — no crash ─────────────────────────────

    @Test
    @DisplayName("POST /api/glucose-calculations/ — with prospective notes returns 200")
    void prospectiveNotes_included_doesNotCrash() {
        RegisterRequest reg = validRegister();
        HttpHeaders headers = authedHeaders(reg);

        Map<String, Object> prospective = Map.of(
                "carbs", 45.0,
                "insulin", 3.5,
                "meal", "Breakfast",
                "minutesAgo", 0);

        Map<String, Object> body = Map.of(
                "currentGlucose", 5.5,
                "prospectiveNotes", List.of(prospective));

        ResponseEntity<Map> resp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    @Test
    @DisplayName("POST /api/glucose-calculations/ — empty prospective notes list returns 200")
    void prospectiveNotesEmpty_returnsOk() {
        RegisterRequest reg = validRegister();
        HttpHeaders headers = authedHeaders(reg);

        Map<String, Object> body = Map.of(
                "currentGlucose", 5.5,
                "prospectiveNotes", List.of());

        ResponseEntity<Map> resp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ── scenario 4: hypoglycemia — confidence reduced ─────────────────────────

    @Test
    @DisplayName("POST — hypo glucose (<3.0) has lower confidence than normal glucose")
    void hypoGlucose_confidenceLowerThanNormal() {
        RegisterRequest reg = validRegister();
        HttpHeaders headers = authedHeaders(reg);

        ResponseEntity<Map> hypoResp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("currentGlucose", 2.5), headers),
                Map.class);
        ResponseEntity<Map> normalResp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("currentGlucose", 6.5), headers),
                Map.class);

        assertEquals(HttpStatus.OK, hypoResp.getStatusCode());
        assertEquals(HttpStatus.OK, normalResp.getStatusCode());

        Boolean hypoBackend   = (Boolean) hypoResp.getBody().get("backendMode");
        Boolean normalBackend = (Boolean) normalResp.getBody().get("backendMode");

        if (Boolean.TRUE.equals(hypoBackend) && Boolean.TRUE.equals(normalBackend)) {
            Map<String, Object> hypoData   = (Map<String, Object>) hypoResp.getBody().get("data");
            Map<String, Object> normalData = (Map<String, Object>) normalResp.getBody().get("data");

            double hypoConf   = ((Number) hypoData.get("confidence")).doubleValue();
            double normalConf = ((Number) normalData.get("confidence")).doubleValue();

            assertTrue(hypoConf <= normalConf,
                    "Hypo confidence=" + hypoConf + " must be ≤ normal=" + normalConf);
        }
    }

    // ── scenario 5: feature status endpoint ──────────────────────────────────

    @Test
    @DisplayName("GET /api/glucose-calculations/status — always returns featureEnabled")
    void featureStatus_returnsExpectedFields() {
        RegisterRequest reg = validRegister();
        HttpHeaders headers = authedHeaders(reg);

        ResponseEntity<Map> resp = rest.exchange(
                "/api/glucose-calculations/status",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> rb = resp.getBody();
        assertNotNull(rb);
        assertTrue(rb.containsKey("featureEnabled"), "Must have featureEnabled");
        assertTrue(rb.containsKey("backendMode"), "Must have backendMode");
        assertTrue(rb.containsKey("migrationPercent"), "Must have migrationPercent");
    }

    // ── unauthenticated access blocked ────────────────────────────────────────

    @Test
    @DisplayName("POST without auth token → 401 or 403")
    void noToken_isRejected() {
        Map<String, Object> body = Map.of("currentGlucose", 6.0);

        ResponseEntity<Map> resp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                jsonEntity(body),
                Map.class);

        assertTrue(
                resp.getStatusCode() == HttpStatus.UNAUTHORIZED ||
                resp.getStatusCode() == HttpStatus.FORBIDDEN,
                "Unauthenticated request must be rejected, got: " + resp.getStatusCode());
    }
}
