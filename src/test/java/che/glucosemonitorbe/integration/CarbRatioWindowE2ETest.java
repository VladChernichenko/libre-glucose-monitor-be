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
 * Each meal is forecast with the ratio of its own meal window, through the real HTTP stack. Every
 * comparison gives both users the same meal and the same dose, so the 4 h carb/insulin timing gap
 * (about 0.14 mmol/L per unit still to act at 4 h) is the same on both sides and cancels; absolute
 * flatness at 8 h is covered by HovorkaMealWindowRatioTest. Also pins what an older iPhone build
 * sends and expects from the settings endpoint.
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
    private static final double MEAL_UNITS = 10.0;

    @Autowired private TestRestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("A meal is forecast with its own window's ratio: breakfast at 6 g/U lands where lunch at 6 g/U does")
    void theMealsWindowRatioIsTheOneUsed() throws Exception {
        double a = landingAfterMeal(ratios(6.0, 10.0, 10.0, 10.0), LocalTime.of(8, 0), "Breakfast", MEAL_UNITS);
        double b = landingAfterMeal(ratios(10.0, 6.0, 10.0, 10.0), LocalTime.of(12, 0), "Lunch", MEAL_UNITS);
        System.out.printf("CarbRatioWindowE2E same dose, 6 g/U window: A breakfast 08:00 %.2f, B lunch 12:00 %.2f%n",
                a, b);
        assertTrue(Math.abs(a - b) <= 0.2,
                "A (breakfast 6 g/U, 10 U at 08:00) landed at " + a + " vs B (lunch 6 g/U, 10 U at 12:00) at " + b);
    }

    @Test
    @DisplayName("For the same breakfast and dose, a weaker breakfast ratio (10 g/U) forecasts lower than 6 g/U")
    void aWeakerWindowRatioForecastsLowerForTheSameDose() throws Exception {
        double a = landingAfterMeal(ratios(6.0, 10.0, 10.0, 10.0), LocalTime.of(8, 0), "Breakfast", MEAL_UNITS);
        double c = landingAfterMeal(ratios(10.0, 6.0, 6.0, 6.0), LocalTime.of(8, 0), "Breakfast", MEAL_UNITS);
        System.out.printf("CarbRatioWindowE2E same dose at 08:00: A breakfast 6 g/U %.2f, C breakfast 10 g/U %.2f%n",
                a, c);
        assertTrue(c <= a - 2.0, "C (breakfast 10 g/U) landed at " + c + ", expected at least 2.0 below A at " + a);
    }

    @Test
    @DisplayName("A save from an older app that still sends gramsPerUnit and carbRatio is accepted and both keys ignored")
    void anOlderAppsRemovedKeysAreAcceptedAndIgnored() throws Exception {
        Map<String, Object> settings = commonSettings();
        settings.put("gramsPerUnit", 12.0);
        settings.put("carbRatio", 7.0);
        settings.put("gramsPerUnitBreakfast", 6.0);
        HttpHeaders auth = registerAndLogin();

        ResponseEntity<String> saved = rest.exchange("/api/user-settings", HttpMethod.POST,
                entity(settings, auth), String.class);

        assertEquals(HttpStatus.OK, saved.getStatusCode(), "saving settings failed: " + saved.getBody());
        JsonNode body = mapper.readTree(saved.getBody());
        assertTrue(body.path("gramsPerUnit").isMissingNode(), "the removed single ratio is not reported: " + body);
        assertTrue(body.path("carbRatio").isMissingNode(), "the removed carbRatio is not reported: " + body);
        assertEquals(6.0, body.path("gramsPerUnitBreakfast").asDouble());
        assertEquals(5.0, body.path("gramsPerUnitLunch").asDouble(), "an unsent window keeps the new row's 5 g/U");
    }

    private static Map<String, Object> commonSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("timezone", "UTC");
        settings.put("bodyWeightKg", 70);
        settings.put("carbHalfLife", 45);
        settings.put("maxCOBDuration", 240);
        settings.put("isf", 1.5);   // the same ISF in every window: no window overrides
        return settings;
    }

    /** The common settings with these four window ratios [g/U]. */
    private static Map<String, Object> ratios(double breakfast, double lunch, double dinner, double night) {
        Map<String, Object> settings = commonSettings();
        settings.put("gramsPerUnitBreakfast", breakfast);
        settings.put("gramsPerUnitLunch", lunch);
        settings.put("gramsPerUnitDinner", dinner);
        settings.put("gramsPerUnitNight", night);
        return settings;
    }

    /** A new user with {@code settings} eats {@link #MEAL_CARBS} with {@code units} at {@code mealTime}; the 4 h landing. */
    private double landingAfterMeal(Map<String, Object> settings, LocalTime mealTime, String meal, double units)
            throws Exception {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        LocalDateTime t0 = now.toLocalDate().atTime(mealTime);
        if (!t0.isBefore(now.minusMinutes(1))) t0 = t0.minusDays(1);

        HttpHeaders auth = registerAndLogin();
        ResponseEntity<String> saved = rest.exchange("/api/user-settings", HttpMethod.POST,
                entity(settings, auth), String.class);
        assertEquals(HttpStatus.OK, saved.getStatusCode(), "saving settings failed: " + saved.getBody());

        Map<String, Object> note = new LinkedHashMap<>();
        note.put("timestamp", t0.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        note.put("carbs", MEAL_CARBS);
        note.put("insulin", units);
        note.put("meal", meal);
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
