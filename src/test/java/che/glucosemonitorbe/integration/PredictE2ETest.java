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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for {@code POST /api/predict}.
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>HTTP contract — 200, 400, 401 responses.</li>
 *   <li>Response structure — curve, preBolusMinutes, bolusStrategy, tMaxGUsed, betaWeighted.</li>
 *   <li>Macro modulation — HFHP meal produces a longer tMaxGUsed than pure-carb meal.</li>
 *   <li>Bolus strategy — SQUARE_WAVE for high-fat/high-protein, NORMAL for carb-only.</li>
 *   <li>Horizon — 300-min request returns ≈60 curve points (5-min steps × 60).</li>
 *   <li>Pre-bolus — with an insulin dose, preBolusMinutes is in [0, 30].</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "app.features.backend-mode-enabled=true",
        "app.features.glucose-calculations-enabled=true",
        "app.features.glucose-calculations-migration-percent=100",
        "app.features.hovorka-model-enabled=true",
        "app.features.nutrition-aware-prediction-enabled=true"
})
@SuppressWarnings({"resource", "null"})
class PredictE2ETest {

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

    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        authHeaders = registerAndLogin();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP contract
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1 · Valid request → 200 with curve array")
    void validRequest_returns200WithCurve() throws Exception {
        JsonNode resp = predict(7.2, 0, 50, 0, 0, 0, 300);

        assertNotNull(resp, "Response body must not be null");
        assertTrue(resp.path("curve").isArray(),
                "Response must contain a 'curve' array — got: " + resp);
        assertTrue(resp.path("curve").size() > 0,
                "Curve must be non-empty — got: " + resp.path("curve").size());
    }

    @Test
    @DisplayName("2 · Missing currentGlucose → 400 Bad Request")
    void missingCurrentGlucose_returns400() {
        Map<String, Object> body = Map.of(
                "carbs", 50,
                "insulinDose", 4
        );
        ResponseEntity<String> resp = rest.exchange(
                "/api/predict", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "Missing currentGlucose must yield 400 — got: " + resp.getStatusCode());
    }

    @Test
    @DisplayName("3 · No Authorization header → 401 Unauthorized")
    void noAuth_returns401() {
        HttpHeaders noAuth = new HttpHeaders();
        noAuth.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange(
                "/api/predict", HttpMethod.POST,
                new HttpEntity<>(predictBody(7.0, 0, 50, 0, 0, 0, 300), noAuth), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "Request without auth token must yield 401 — got: " + resp.getStatusCode());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response structure
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("4 · Response contains all required fields")
    void response_containsAllRequiredFields() throws Exception {
        JsonNode resp = predict(7.0, 3.0, 50, 10, 8, 3, 300);

        assertFalse(resp.path("curve").isMissingNode(),           "curve field required");
        assertFalse(resp.path("preBolusMinutes").isMissingNode(), "preBolusMinutes field required");
        assertFalse(resp.path("bolusStrategy").isMissingNode(),   "bolusStrategy field required");
        assertFalse(resp.path("tMaxGUsed").isMissingNode(),       "tMaxGUsed field required");
        assertFalse(resp.path("betaWeighted").isMissingNode(),    "betaWeighted field required");
    }

    @Test
    @DisplayName("5 · Curve points contain predictedGlucose and timestamp")
    void curvePoints_haveRequiredFields() throws Exception {
        JsonNode resp = predict(7.5, 0, 45, 0, 0, 0, 300);
        JsonNode firstPoint = resp.path("curve").get(0);

        assertFalse(firstPoint.path("predictedGlucose").isMissingNode(),
                "curve[0].predictedGlucose must be present");
        assertFalse(firstPoint.path("timestamp").isMissingNode(),
                "curve[0].timestamp must be present");
        assertEquals("DALLA_MAN_3COMP", firstPoint.path("absorptionMode").asText(),
                "absorptionMode must be DALLA_MAN_3COMP");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Macro modulation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("6 · HFHP meal tMaxGUsed > pure-carb tMaxGUsed")
    void hfhpMeal_longerTMaxGThanPureCarbs() throws Exception {
        // Pure carbs: 60g carbs only
        double tMaxGCarb = predict(7.0, 0, 60, 0, 0, 0, 300)
                .path("tMaxGUsed").asDouble();

        // HFHP: same carbs + high protein + high fat
        double tMaxGHFHP = predict(7.0, 0, 40, 30, 25, 5, 300)
                .path("tMaxGUsed").asDouble();

        assertTrue(tMaxGHFHP > tMaxGCarb,
                "HFHP meal must produce a longer tMaxGUsed than pure-carb meal. "
                + "carb=" + tMaxGCarb + " HFHP=" + tMaxGHFHP);
    }

    @Test
    @DisplayName("7 · Fiber increases tMaxGUsed compared to fiber-free same meal")
    void fiber_increasesTMaxG() throws Exception {
        double withoutFiber = predict(7.0, 0, 50, 10, 10, 0, 300)
                .path("tMaxGUsed").asDouble();
        double withFiber    = predict(7.0, 0, 50, 10, 10, 10, 300)
                .path("tMaxGUsed").asDouble();

        assertTrue(withFiber > withoutFiber,
                "Adding fiber must increase tMaxGUsed via viscosity effect. "
                + "noFiber=" + withoutFiber + " withFiber=" + withFiber);
    }

    @Test
    @DisplayName("8 · betaWeighted for pure fat > pure carbs")
    void betaWeighted_fatGreaterThanCarb() throws Exception {
        double betaCarb = predict(7.0, 0, 60, 0,  0, 0, 300).path("betaWeighted").asDouble();
        double betaFat  = predict(7.0, 0,  0, 0, 60, 0, 300).path("betaWeighted").asDouble();

        assertTrue(betaFat > betaCarb,
                "Elashoff β_fat(2.2) must be > β_carb(1.05). carb=" + betaCarb + " fat=" + betaFat);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bolus strategy
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("9 · High-fat + high-protein meal → bolusStrategy=SQUARE_WAVE")
    void hfhpMeal_squareWaveStrategy() throws Exception {
        String strategy = predict(7.0, 4.0, 30, 25, 30, 3, 300)
                .path("bolusStrategy").asText();

        assertEquals("SQUARE_WAVE", strategy,
                "Buffalo-wings-style meal must recommend SQUARE_WAVE bolus strategy");
    }

    @Test
    @DisplayName("10 · Simple carb meal → bolusStrategy=NORMAL")
    void simpleCarbMeal_normalStrategy() throws Exception {
        String strategy = predict(7.0, 4.0, 60, 5, 3, 2, 300)
                .path("bolusStrategy").asText();

        assertEquals("NORMAL", strategy,
                "Low-fat, low-protein meal must use NORMAL bolus strategy");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pre-bolus optimisation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("11 · With insulin dose, preBolusMinutes is in [0, 30]")
    void withInsulinDose_preBolusInValidRange() throws Exception {
        int prebolus = predict(8.5, 4.0, 60, 0, 0, 0, 300)
                .path("preBolusMinutes").asInt(-1);

        assertTrue(prebolus >= 0 && prebolus <= 30,
                "preBolusMinutes must be in [0, 30] — got: " + prebolus);
    }

    @Test
    @DisplayName("12 · No insulin dose → preBolusMinutes=0")
    void noInsulinDose_prebolusIsZero() throws Exception {
        int prebolus = predict(7.0, 0, 50, 0, 0, 0, 300)
                .path("preBolusMinutes").asInt(-1);

        assertEquals(0, prebolus,
                "preBolusMinutes must be 0 when no insulin dose is requested — got: " + prebolus);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Horizon / curve length
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("13 · 300-min horizon → curve has 54 points (48 dense at 5-min + 6 sparse at 10-min)")
    void horizon300_produces54Points() throws Exception {
        int size = predict(7.0, 0, 50, 0, 0, 0, 300)
                .path("curve").size();

        // Dense 0-240 min at 5-min step = 48 points; sparse 240-300 min at 10-min step = 6 points.
        assertTrue(size >= 52 && size <= 56,
                "300-min horizon should produce ~54 curve points — got: " + size);
    }

    @Test
    @DisplayName("14 · 480-min horizon → curve has approximately 72 points (48 dense + 24 sparse)")
    void horizon480_produces72Points() throws Exception {
        int size = predict(7.0, 0, 50, 0, 0, 0, 480)
                .path("curve").size();

        // 240 dense / 5 + 240 sparse / 10 = 48 + 24 = 72. Allow ±5.
        assertTrue(size >= 67 && size <= 77,
                "480-min horizon should produce ~72 curve points — got: " + size);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Physiological plausibility
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("15 · Curve first point is near currentGlucose (±2 mmol/L)")
    void curveFirstPoint_nearCurrentGlucose() throws Exception {
        double current = 7.8;
        JsonNode resp  = predict(current, 0, 40, 0, 0, 0, 300);

        double firstG = resp.path("curve").get(0).path("predictedGlucose").asDouble();
        assertTrue(Math.abs(firstG - current) <= 2.0,
                "First curve point must be within 2 mmol/L of currentGlucose. "
                + "current=" + current + " first=" + firstG);
    }

    @Test
    @DisplayName("16 · Curve values are physiologically plausible (0.5 – 30 mmol/L)")
    void curveValues_arePhysiologicallyPlausible() throws Exception {
        JsonNode curve = predict(7.0, 3.0, 50, 10, 8, 3, 300).path("curve");

        for (int i = 0; i < curve.size(); i++) {
            double g = curve.get(i).path("predictedGlucose").asDouble();
            assertTrue(g >= 0.5 && g <= 30.0,
                    "Predicted glucose must be in physiological range [0.5, 30] mmol/L — got "
                    + g + " at index " + i);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private JsonNode predict(
            double currentGlucose, double insulinDose,
            double carbs, double protein, double fat, double fiber,
            int horizonMinutes) throws Exception {

        ResponseEntity<String> resp = rest.exchange(
                "/api/predict", HttpMethod.POST,
                new HttpEntity<>(predictBody(currentGlucose, insulinDose, carbs, protein, fat, fiber, horizonMinutes),
                        authHeaders),
                String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "Expected 200 from POST /api/predict — got: " + resp.getStatusCode()
                + " body: " + resp.getBody());
        return mapper.readTree(resp.getBody());
    }

    private Map<String, Object> predictBody(
            double currentGlucose, double insulinDose,
            double carbs, double protein, double fat, double fiber,
            int horizonMinutes) {
        return Map.of(
                "currentGlucose",  currentGlucose,
                "insulinDose",     insulinDose,
                "carbs",           carbs,
                "protein",         protein,
                "fat",             fat,
                "fiber",           fiber,
                "horizonMinutes",  horizonMinutes
        );
    }

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("pred_" + suffix);
        reg.setEmail("pred+" + suffix + "@example.com");
        reg.setFullName("Pred User");
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

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
