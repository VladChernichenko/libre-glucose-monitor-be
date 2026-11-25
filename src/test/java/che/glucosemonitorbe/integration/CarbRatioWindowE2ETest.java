package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Window carb-ratio / ISF overrides must forecast exactly like the same values set as base settings.
 * It compares against a control user instead of asserting an absolute 4 h landing because the 4 h
 * horizon includes an override-independent carb/insulin timing gap (any ratio-priced meal reads about
 * 1.4 mmol/L low at 4 h); absolute flatness at 8 h is covered by HovorkaMealWindowRatioTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "app.features.glucose-calculations-enabled=true",
        "app.features.nutrition-aware-prediction-enabled=true",
        "app.features.hovorka-model-enabled=true"
})
@SuppressWarnings({"resource", "null"})
class CarbRatioWindowE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    private static final double START_GLUCOSE = 7.0;
    private static final double MEAL_CARBS = 60.0;

    @Autowired private TestRestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Breakfast window overrides forecast like the same base settings")
    void breakfastWindowOverridesMatchTheSameBaseSettings() throws Exception {
        double window = landingAfterBreakfast(windowSettings(), MEAL_CARBS / 6.0);
        double control = landingAfterBreakfast(controlSettings(), MEAL_CARBS / 6.0);
        System.out.printf("CarbRatioWindowE2E equivalence: window user %.2f, control user %.2f%n", window, control);
        assertTrue(Math.abs(window - control) <= 0.2,
                "window overrides landed at " + window + " vs control with same base settings " + control);
    }

    @Test
    @DisplayName("Breakfast dosed by the single ratio forecasts higher than dosed by the window ratio")
    void breakfastDosedByTheSingleRatioForecastsHigh() throws Exception {
        double singleRatio = landingAfterBreakfast(windowSettings(), MEAL_CARBS / 10.0);
        double control = landingAfterBreakfast(controlSettings(), MEAL_CARBS / 6.0);
        System.out.printf("CarbRatioWindowE2E single-ratio dose (6 U): %.2f, control (10 U): %.2f%n", singleRatio, control);
        assertTrue(singleRatio >= control + 2.0,
                "6 U landed at " + singleRatio + ", expected at least 2.0 above control " + control);
    }

    private static Map<String, Object> commonSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("timezone", "UTC");
        settings.put("carbRatio", 1.5);
        settings.put("bodyWeightKg", 70);
        settings.put("carbHalfLife", 45);
        settings.put("maxCOBDuration", 240);
        return settings;
    }

    private static Map<String, Object> windowSettings() {
        Map<String, Object> settings = commonSettings();
        settings.put("isf", 1.0);
        settings.put("isfBreakfast", 1.5);
        settings.put("gramsPerUnit", 10.0);
        settings.put("gramsPerUnitBreakfast", 6.0);
        return settings;
    }

    private static Map<String, Object> controlSettings() {
        Map<String, Object> settings = commonSettings();
        settings.put("isf", 1.5);
        settings.put("gramsPerUnit", 6.0);
        return settings;
    }

    private double landingAfterBreakfast(Map<String, Object> settings, double units) throws Exception {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        LocalDateTime t0 = now.toLocalDate().atTime(LocalTime.of(8, 0));
        if (!t0.isBefore(now.minusMinutes(1))) t0 = t0.minusDays(1);

        HttpHeaders auth = registerAndLogin();
        ResponseEntity<String> saved = rest.exchange("/api/user-settings", HttpMethod.POST,
                entity(settings, auth), String.class);
        assertEquals(HttpStatus.OK, saved.getStatusCode(), "saving settings failed: " + saved.getBody());

        Map<String, Object> note = new LinkedHashMap<>();
        note.put("timestamp", t0.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        note.put("carbs", MEAL_CARBS);
        note.put("insulin", units);
        note.put("meal", "Breakfast");
        ResponseEntity<String> noteResp = rest.exchange("/api/notes", HttpMethod.POST,
                entity(note, auth), String.class);
        assertTrue(noteResp.getStatusCode().is2xxSuccessful(), "saving note failed: " + noteResp.getBody());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentGlucose", START_GLUCOSE);
        body.put("includePredictionFactors", true);
        body.put("clientTimeInfo", Map.of(
                "timestamp", t0.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                "timezone", "UTC", "locale", "en-US", "timezoneOffset", 0));
        ResponseEntity<String> resp = rest.exchange("/api/glucose-calculations/", HttpMethod.POST,
                entity(body, auth), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "calculation failed: " + resp.getBody());
        JsonNode root = mapper.readTree(resp.getBody());
        assertTrue(root.path("backendMode").asBoolean(), "backendMode must be true: " + resp.getBody());

        JsonNode path = root.path("data").path("predictionPath");
        assertTrue(path.isArray() && !path.isEmpty(), "predictionPath is empty: " + root.path("data"));
        LocalDateTime target = t0.plusMinutes(240);
        JsonNode best = path.get(0);
        long bestDiff = Long.MAX_VALUE;
        for (JsonNode p : path) {
            long diff = Math.abs(Duration.between(target, LocalDateTime.parse(p.path("timestamp").asText())).toSeconds());
            if (diff < bestDiff) {
                bestDiff = diff;
                best = p;
            }
        }
        assertTrue(bestDiff <= 5 * 60, "prediction path does not reach t0+4h");
        return best.path("predictedGlucose").asDouble();
    }

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("ratio_" + suffix);
        reg.setEmail("ratio+" + suffix + "@example.com");
        reg.setFullName("Ratio User");
        reg.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", entity(reg, new HttpHeaders()), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        ResponseEntity<AuthResponse> resp =
                rest.postForEntity("/api/auth/login", entity(login, new HttpHeaders()), AuthResponse.class);
        assertNotNull(resp.getBody(), "login failed");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(resp.getBody().getAccessToken());
        return h;
    }

    private static <T> HttpEntity<T> entity(T body, HttpHeaders auth) {
        HttpHeaders h = new HttpHeaders();
        h.addAll(auth);
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
