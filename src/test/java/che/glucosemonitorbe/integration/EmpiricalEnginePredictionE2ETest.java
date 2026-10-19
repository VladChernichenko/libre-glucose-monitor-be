package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.CreateNoteRequest;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@code POST /api/glucose-calculations/} when the user's
 * {@code prediction_engine} selects the empirical twin (specs/empirical-twin-port.md, R5-R6).
 *
 * <p>Each scenario asserts the shape the model promises - flat when fasting, a fall on insulin
 * alone, fruit faster than bread, a late tail after a fatty meal, a dip on a walk - rather than
 * absolute numbers, which belong to the golden test.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "app.features.glucose-calculations-enabled=true",
        "app.features.nutrition-aware-prediction-enabled=true",
        "app.features.activity-logging-enabled=true",
        "app.features.empirical-twin-enabled=true"
})
@SuppressWarnings({"resource", "null"})
class EmpiricalEnginePredictionE2ETest {

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

    /** Points on a 4 h path at 5-min steps, +5 .. +240. */
    private static final int PATH_POINTS = 48;
    private static final String EMPIRICAL_MODE = "EMPIRICAL_TWIN";

    private static final String FRUIT_PROFILE = """
            {"totalCarbs":30.0,"fruit":true}
            """;
    private static final String FATTY_PROFILE = """
            {"totalCarbs":40.0,"fattyMeal":true}
            """;

    /** Shared "now" - all notes and the calculation request use the same anchor. */
    private LocalDateTime testNow;
    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        testNow = LocalDateTime.now();
        authHeaders = registerAndLogin();
        selectEngine("EMPIRICAL", authHeaders);
    }

    // ---
    // Engine selection
    // ---

    @Test
    @DisplayName("default engine is HOVORKA - no empirical points, no second series")
    void defaultEngine_isHovorka() throws Exception {
        HttpHeaders fresh = registerAndLogin();

        JsonNode data = calculate(7.0, fresh);

        assertTrue(data.path("predictionPath").size() > 0, "predictionPath must be non-empty");
        assertFalse(EMPIRICAL_MODE.equals(data.path("predictionPath").get(0).path("absorptionMode").asText()),
                "Default engine must not be the empirical twin");
        assertTrue(data.path("empiricalPredictionPath").isNull() || data.path("empiricalPredictionPath").isMissingNode(),
                "empiricalPredictionPath must be absent unless the engine is BOTH");
    }

    @Test
    @DisplayName("EMPIRICAL - predictionPath is the empirical curve on the 5-min grid, no second series")
    void empirical_replacesThePredictionPath() throws Exception {
        postNote(0.0, 2.0, "Correction", null, 10);

        JsonNode data = calculate(9.0);
        JsonNode path = data.path("predictionPath");

        assertEquals(PATH_POINTS, path.size(), "Empirical path must have 48 points (+5..+240)");
        for (int i = 0; i < path.size(); i++) {
            assertEquals(EMPIRICAL_MODE, path.get(i).path("absorptionMode").asText(),
                    "Every point must be stamped as empirical - index " + i);
            assertEquals(testNow.plusMinutes(5L * (i + 1)).withNano(0),
                    LocalDateTime.parse(path.get(i).path("timestamp").asText()).withNano(0),
                    "Timestamps must step by 5 min from now+5 - index " + i);
        }
        assertEquals(glucoseAt(path, 240), data.path("fourHourPrediction").asDouble(), 1e-9,
                "fourHourPrediction must be read off the empirical path");
        assertEquals(glucoseAt(path, 120), data.path("twoHourPrediction").asDouble(), 1e-9,
                "twoHourPrediction must be read off the empirical path");
        assertTrue(data.path("empiricalPredictionPath").isNull() || data.path("empiricalPredictionPath").isMissingNode(),
                "empiricalPredictionPath is only populated for BOTH");
    }

    @Test
    @DisplayName("BOTH - Hovorka path stays primary, empirical path arrives as a second series")
    void both_returnsHovorkaPlusEmpiricalSeries() throws Exception {
        HttpHeaders both = registerAndLogin();
        selectEngine("BOTH", both);
        postNote(0.0, 2.0, "Correction", null, 10, both);

        JsonNode data = calculate(9.0, both);
        JsonNode primary = data.path("predictionPath");
        JsonNode second = data.path("empiricalPredictionPath");

        assertTrue(primary.size() > 0, "predictionPath must be non-empty");
        assertFalse(EMPIRICAL_MODE.equals(primary.get(0).path("absorptionMode").asText()),
                "With BOTH the primary path must stay the Hovorka path");
        assertEquals(PATH_POINTS, second.size(), "Second series must be the 48-point empirical path");
        assertEquals(EMPIRICAL_MODE, second.get(0).path("absorptionMode").asText());

        // The second series is the same curve EMPIRICAL would return for the same history.
        postNote(0.0, 2.0, "Correction", null, 10);
        JsonNode empiricalOnly = calculate(9.0).path("predictionPath");
        for (int minute = 30; minute <= 240; minute += 30) {
            assertEquals(glucoseAt(empiricalOnly, minute), glucoseAt(second, minute), 1e-9,
                    "BOTH's second series must equal the EMPIRICAL path at +" + minute);
        }
    }

    @Test
    @DisplayName("an unknown engine name is rejected")
    void unknownEngine_isRejected() {
        ResponseEntity<String> resp = rest.exchange("/api/user-settings", HttpMethod.PUT,
                new HttpEntity<>(Map.of("predictionEngine", "MAGIC"), authHeaders), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "An unknown prediction engine must yield 400 - got: " + resp.getStatusCode());
    }

    // ---
    // Model shapes
    // ---

    @Test
    @DisplayName("fasting - no notes -> flat line at the current glucose")
    void fasting_isAFlatLine() throws Exception {
        double current = 6.4;

        JsonNode path = calculate(current).path("predictionPath");

        assertEquals(PATH_POINTS, path.size());
        for (int i = 0; i < path.size(); i++) {
            assertEquals(current, path.get(i).path("predictedGlucose").asDouble(), 1e-9,
                    "Basal is assumed to hold glucose flat - index " + i);
        }
    }

    @Test
    @DisplayName("insulin only - 2 U correction -> steady fall of about ISF x dose")
    void insulinOnly_falls() throws Exception {
        double current = 10.0;
        postNote(0.0, 2.0, "Correction", null, 5);

        JsonNode path = calculate(current).path("predictionPath");

        double previous = current;
        for (int i = 0; i < path.size(); i++) {
            double g = path.get(i).path("predictedGlucose").asDouble();
            assertTrue(g <= previous + 1e-9,
                    "Glucose must never rise on insulin alone - index " + i + ": " + previous + " -> " + g);
            previous = g;
        }
        // 2 U x 27 mg/dL per U = 54 mg/dL = 3.0 mmol/L; nearly all of it lands within the 4 h path.
        double drop = current - glucoseAt(path, 240);
        assertTrue(drop > 2.6 && drop <= 3.05,
                "2 U should lower glucose by close to 3.0 mmol/L over 4 h - got: " + drop);
    }

    @Test
    @DisplayName("fruit vs bread - same carbs, fruit arrives sooner, both end at the same level")
    void fruit_risesFasterThanBread() throws Exception {
        double current = 6.0;
        HttpHeaders breadUser = registerAndLogin();
        selectEngine("EMPIRICAL", breadUser);
        postNote(30.0, 0.0, "Snack", FRUIT_PROFILE, 1);
        postNote(30.0, 0.0, "Snack", null, 1, breadUser);

        JsonNode fruit = calculate(current).path("predictionPath");
        JsonNode bread = calculate(current, breadUser).path("predictionPath");

        assertTrue(glucoseAt(fruit, 30) > glucoseAt(bread, 30) + 0.5,
                "Fruit must be clearly ahead of bread at +30 min - fruit: "
                        + glucoseAt(fruit, 30) + ", bread: " + glucoseAt(bread, 30));
        assertEquals(glucoseAt(bread, 240), glucoseAt(fruit, 240), 0.15,
                "The same carbs must raise glucose by the same amount once absorbed");
    }

    @Test
    @DisplayName("fatty meal - lower early, still rising late compared with the same carbs unflagged")
    void fattyMeal_hasALateTail() throws Exception {
        double current = 6.0;
        HttpHeaders plainUser = registerAndLogin();
        selectEngine("EMPIRICAL", plainUser);
        postNote(40.0, 0.0, "Dinner", FATTY_PROFILE, 1);
        postNote(40.0, 0.0, "Dinner", null, 1, plainUser);

        JsonNode fatty = calculate(current).path("predictionPath");
        JsonNode plain = calculate(current, plainUser).path("predictionPath");

        assertTrue(glucoseAt(fatty, 60) < glucoseAt(plain, 60) - 0.2,
                "A fatty meal must be behind the plain meal at +60 min - fatty: "
                        + glucoseAt(fatty, 60) + ", plain: " + glucoseAt(plain, 60));
        double fattyTail = glucoseAt(fatty, 240) - glucoseAt(fatty, 120);
        double plainTail = glucoseAt(plain, 240) - glucoseAt(plain, 120);
        assertTrue(fattyTail > plainTail + 0.3,
                "A fatty meal must keep rising between +2 h and +4 h - fatty: "
                        + fattyTail + ", plain: " + plainTail);
    }

    @Test
    @DisplayName("walk - a logged 60-min walk dips an otherwise flat forecast, then levels off")
    void walk_dipsTheForecast() throws Exception {
        double current = 8.0;
        postActivity("WALKING", "LOW", 60, 5);

        JsonNode path = calculate(current).path("predictionPath");

        // 20 mg/dL per hour while walking: about 1 mmol/L over the remaining 55 min.
        double atWalkEnd = glucoseAt(path, 55);
        assertTrue(atWalkEnd < current - 0.8 && atWalkEnd > current - 1.3,
                "Walking should lower glucose by about 1 mmol/L by the end of the walk - got: " + atWalkEnd);
        assertTrue(glucoseAt(path, 120) < atWalkEnd,
                "The effect must keep fading in for the hour after the walk");
        assertEquals(glucoseAt(path, 180), glucoseAt(path, 240), 1e-9,
                "Once the after-effect has faded the forecast must be flat again");
    }

    // ---
    // Helpers
    // ---

    /** Predicted glucose at {@code minute} on a path that starts at +5 and steps by 5. */
    private static double glucoseAt(JsonNode path, int minute) {
        return path.get(minute / 5 - 1).path("predictedGlucose").asDouble();
    }

    private void selectEngine(String engine, HttpHeaders headers) {
        ResponseEntity<String> resp = rest.exchange("/api/user-settings", HttpMethod.PUT,
                new HttpEntity<>(Map.of("predictionEngine", engine), headers), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "Selecting engine " + engine + " must succeed - got: " + resp.getStatusCode());
    }

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("emp_" + suffix);
        reg.setEmail("emp+" + suffix + "@example.com");
        reg.setFullName("Empirical User");
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

    private void postNote(double carbs, double insulin, String meal, String nutritionProfile, int minutesAgo) {
        postNote(carbs, insulin, meal, nutritionProfile, minutesAgo, authHeaders);
    }

    private void postNote(double carbs, double insulin, String meal, String nutritionProfile,
                          int minutesAgo, HttpHeaders headers) {
        CreateNoteRequest n = new CreateNoteRequest();
        n.setTimestamp(testNow.minusMinutes(minutesAgo));
        n.setCarbs(carbs);
        n.setInsulin(insulin > 0 ? insulin : null);
        n.setMeal(meal);
        if (nutritionProfile != null) {
            n.setNutritionProfile(nutritionProfile);
        }
        ResponseEntity<String> resp = rest.exchange("/api/notes", HttpMethod.POST,
                new HttpEntity<>(n, headers), String.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "Note must be created - got: " + resp);
    }

    private void postActivity(String type, String intensity, int durationMin, int minutesAgo) {
        CreateNoteRequest n = new CreateNoteRequest();
        n.setTimestamp(testNow.minusMinutes(minutesAgo));
        n.setCarbs(0.0);
        n.setInsulin(0.0);
        n.setMeal("workout");
        n.setType("activity");
        n.setActivityType(type);
        n.setIntensity(intensity);
        n.setDurationMin(durationMin);
        ResponseEntity<String> resp = rest.exchange("/api/notes", HttpMethod.POST,
                new HttpEntity<>(n, authHeaders), String.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "Activity note must be created - got: " + resp);
    }

    private JsonNode calculate(double currentGlucose) throws Exception {
        return calculate(currentGlucose, authHeaders);
    }

    private JsonNode calculate(double currentGlucose, HttpHeaders headers) throws Exception {
        Map<String, Object> body = Map.of(
                "currentGlucose", currentGlucose,
                "clientTimeInfo", Map.of(
                        "timestamp", testNow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        "timezone", "UTC",
                        "locale", "en-US",
                        "timezoneOffset", 0),
                "includePredictionFactors", true);
        ResponseEntity<String> resp = rest.exchange("/api/glucose-calculations/", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "Expected 200 from /api/glucose-calculations/ - got: " + resp.getStatusCode());
        JsonNode root = mapper.readTree(resp.getBody());
        assertTrue(root.path("backendMode").asBoolean(),
                "backendMode must be true - check @TestPropertySource. Response: " + resp.getBody());
        return root.path("data");
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
