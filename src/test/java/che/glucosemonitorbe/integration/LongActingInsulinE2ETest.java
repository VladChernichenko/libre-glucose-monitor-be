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
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the long-acting (basal) insulin feature, exercised through the real HTTP
 * stack (controller -> service -> JPA -> Postgres via Testcontainers) with a registered, authenticated
 * user.
 *
 * <p>Part A - the daily injection-time preference round-trip
 * ({@code PUT/GET /api/user/insulin-preferences}): the exact persistence path that was failing in
 * the field. Guards save, second-save update, empty-clear, omit-leaves-unchanged, invalid-format,
 * defaults, and auth.
 *
 * <p>Part B - long-acting notes must never be treated as rapid-acting boluses: a basal dose is
 * excluded from {@code activeInsulinOnBoard} in {@code POST /api/glucose-calculations/}, while an
 * ordinary bolus is counted (control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "app.features.backend-mode-enabled=true",
        "app.features.glucose-calculations-enabled=true",
        "app.features.glucose-calculations-migration-percent=100",
        "app.features.carbs-on-board-enabled=true",
        "app.features.insulin-calculator-enabled=true"
})
@SuppressWarnings({"resource", "null"})
class LongActingInsulinE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private LocalDateTime testNow;
    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        testNow = LocalDateTime.now();
        authHeaders = registerAndLogin();
    }

    // -- Part A: injection-time preference round-trip --------------------------

    @Test
    @DisplayName("A1 * PUT injection time 23:01 is persisted and read back by GET")
    void injectionTime_savedAndReadBack() throws Exception {
        ResponseEntity<String> put = putPrefs(prefsBody("FIASP", "TRESIBA", "23:01"));
        assertEquals(HttpStatus.OK, put.getStatusCode(), put.getBody());
        assertEquals("23:01", mapper.readTree(put.getBody()).path("longActingInjectionTime").asText());

        assertEquals("23:01", getPrefs().path("longActingInjectionTime").asText(),
                "GET must return the time persisted by the PUT");
    }

    @Test
    @DisplayName("A2 * a second PUT updates the stored time (regression: 'second save not saved')")
    void injectionTime_secondSaveUpdates() throws Exception {
        putPrefs(prefsBody("FIASP", "TRESIBA", "23:01"));
        putPrefs(prefsBody("FIASP", "TRESIBA", "07:15"));
        assertEquals("07:15", getPrefs().path("longActingInjectionTime").asText(),
                "the second save must overwrite the first");
    }

    @Test
    @DisplayName("A3 * empty string clears the stored time")
    void injectionTime_emptyStringClears() throws Exception {
        putPrefs(prefsBody("FIASP", "TRESIBA", "23:01"));
        putPrefs(prefsBody("FIASP", "TRESIBA", ""));
        assertTrue(getPrefs().path("longActingInjectionTime").isNull(),
                "empty string should clear the injection time to null");
    }

    @Test
    @DisplayName("A4 * omitting the field leaves the stored time unchanged")
    void injectionTime_omittedLeavesUnchanged() throws Exception {
        putPrefs(prefsBody("FIASP", "TRESIBA", "23:01"));
        // Body without the longActingInjectionTime key at all.
        putPrefs(prefsBody("FIASP", "TRESIBA", null));
        assertEquals("23:01", getPrefs().path("longActingInjectionTime").asText(),
                "a PUT that omits the field must not clear it");
    }

    @Test
    @DisplayName("A5 * invalid time format returns 400")
    void injectionTime_invalidFormat_returns400() {
        ResponseEntity<String> put = putPrefs(prefsBody("FIASP", "TRESIBA", "9pm"));
        assertEquals(HttpStatus.BAD_REQUEST, put.getStatusCode(), put.getBody());
    }

    @Test
    @DisplayName("A6 * a fresh user gets default insulins and a null injection time")
    void getPrefs_freshUser_defaults() throws Exception {
        JsonNode prefs = getPrefs();
        assertEquals("FIASP", prefs.path("rapidInsulinCode").asText());
        assertEquals("TRESIBA", prefs.path("longActingInsulinCode").asText());
        assertTrue(prefs.path("longActingInjectionTime").isNull());
    }

    @Test
    @DisplayName("A7 * GET without authentication is rejected")
    void getPrefs_unauthenticated_isRejected() {
        ResponseEntity<String> r = rest.getForEntity("/api/user/insulin-preferences", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
    }

    // -- Part B: long-acting notes excluded from bolus IOB ---------------------

    @Test
    @DisplayName("B1 * a long-acting (basal) dose is NOT counted as bolus IOB in predictions")
    void longActingNote_excludedFromActiveInsulinOnBoard() throws Exception {
        // 20 U of long-acting taken 30 min ago - a huge phantom IOB if mis-classified.
        postNote(0.0, 20.0, "Tresiba", "long_acting", 30);

        double iob = calculate(8.0).path("activeInsulinOnBoard").asDouble();

        assertEquals(0.0, iob, 0.01,
                "Long-acting (basal) doses must be excluded from activeInsulinOnBoard - got: " + iob);
    }

    @Test
    @DisplayName("B2 * control - an ordinary 20 U bolus 30 min ago DOES count as IOB")
    void normalNote_countsTowardActiveInsulinOnBoard() throws Exception {
        postNote(0.0, 20.0, "Correction", null, 30); // type omitted -> normal bolus

        double iob = calculate(8.0).path("activeInsulinOnBoard").asDouble();

        assertTrue(iob > 0.0,
                "A normal bolus must count toward IOB (control for the exclusion test) - got: " + iob);
    }

    // -- Helpers ----------------------------------------------------------------

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("la_" + suffix);
        reg.setEmail("la+" + suffix + "@example.com");
        reg.setFullName("Long Acting User");
        reg.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", jsonEntity(reg), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        ResponseEntity<AuthResponse> resp =
                rest.postForEntity("/api/auth/login", jsonEntity(login), AuthResponse.class);
        assertNotNull(resp.getBody(), "login response body must not be null");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(resp.getBody().getAccessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** Insulin-prefs PUT body. {@code injectionTime == null} omits the key entirely. */
    private Map<String, Object> prefsBody(String rapid, String longActing, String injectionTime) {
        Map<String, Object> body = new HashMap<>();
        body.put("rapidInsulinCode", rapid);
        body.put("longActingInsulinCode", longActing);
        if (injectionTime != null) {
            body.put("longActingInjectionTime", injectionTime);
        }
        return body;
    }

    private ResponseEntity<String> putPrefs(Map<String, Object> body) {
        return rest.exchange("/api/user/insulin-preferences", HttpMethod.PUT,
                new HttpEntity<>(body, authHeaders), String.class);
    }

    private JsonNode getPrefs() throws Exception {
        ResponseEntity<String> r = rest.exchange("/api/user/insulin-preferences", HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        return mapper.readTree(r.getBody());
    }

    private void postNote(double carbs, double insulin, String meal, String type, int minutesAgo) {
        // Typed request so the @JsonFormat on the timestamp is applied (no fractional seconds).
        CreateNoteRequest n = new CreateNoteRequest();
        n.setTimestamp(testNow.minusMinutes(minutesAgo));
        n.setCarbs(carbs);
        n.setInsulin(insulin > 0 ? insulin : null);
        n.setMeal(meal);
        n.setType(type); // null -> backend defaults to "normal"; "long_acting" for basal
        rest.exchange("/api/notes", HttpMethod.POST, new HttpEntity<>(n, authHeaders), String.class);
    }

    private JsonNode calculate(double currentGlucose) throws Exception {
        Map<String, Object> body = Map.of(
                "currentGlucose", currentGlucose,
                "clientTimeInfo", Map.of(
                        "timestamp", testNow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        "timezone", "UTC",
                        "locale", "en-US",
                        "timezoneOffset", 0),
                "includePredictionFactors", true);
        ResponseEntity<String> resp = rest.exchange("/api/glucose-calculations/", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode root = mapper.readTree(resp.getBody());
        assertTrue(root.path("backendMode").asBoolean(), "backendMode must be true: " + resp.getBody());
        return root.path("data");
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
