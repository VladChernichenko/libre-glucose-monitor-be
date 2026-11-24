package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code GET /api/watch/glance}: auth, time zone validation, and the strip parity with the phone's dashboard. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.features.glucose-calculations-enabled=true")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class WatchGlanceIntegrationTest {

    private static final String TZ = "Europe/Berlin";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired private TestRestTemplate rest;
    @Autowired private CgmReadingRepository readings;
    @Autowired private UserRepository users;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("Unauthenticated callers get 401")
    void glance_unauthenticated_is401() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/watch/glance?tz=" + TZ, null).getStatusCode());
    }

    @Test
    @DisplayName("A missing or unknown time zone is rejected, never silently computed in UTC")
    void glance_invalidTimezone_returns400() {
        String token = signIn().accessToken();
        assertEquals(HttpStatus.BAD_REQUEST, get("/api/watch/glance?tz=Mars/Base", token).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, get("/api/watch/glance", token).getStatusCode());
    }

    @Test
    @DisplayName("A user with no readings gets an empty glance")
    void glance_newUser_returnsEmptyGlance() throws Exception {
        String token = signIn().accessToken();

        ResponseEntity<String> response = get("/api/watch/glance?tz=" + TZ, token);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode glance = json.readTree(response.getBody());
        assertTrue(glance.get("reading").isNull());
        assertEquals("NONE", glance.get("staleCause").asText());
        assertTrue(glance.get("watch").isNull());
    }

    @Test
    @DisplayName("For the same stored reading the watch gets the phone dashboard's strip")
    void glance_stripEqualsPhoneDashboardStrip() throws Exception {
        Session session = signIn();
        UUID userId = users.findByUsername(session.username()).orElseThrow().getId();
        long now = Instant.now().toEpochMilli();
        for (int minutesAgo : new int[] {15, 10, 5, 1}) {
            readings.save(CgmReading.builder().userId(userId).dataSource(CgmReading.DataSource.NIGHTSCOUT)
                    .sgv(58).dateTimestamp(now - minutesAgo * 60_000L).direction("Flat").type("sgv").build());
        }

        ZoneId zone = ZoneId.of(TZ);
        Map<String, Object> phoneBody = Map.of(
                "currentGlucose", 58 / 18.018,
                "includePredictionFactors", true,
                "currentTrendMmolPerMin", 0.0,
                "clientTimeInfo", Map.of(
                        "timestamp", LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
                        "timezone", TZ,
                        "timezoneOffset", zone.getRules().getOffset(Instant.now()).getTotalSeconds() / 60));
        ResponseEntity<String> phone = rest.exchange("/api/glucose-calculations/", HttpMethod.POST,
                entity(phoneBody, session.accessToken()), String.class);
        assertEquals(HttpStatus.OK, phone.getStatusCode());
        ResponseEntity<String> watch = get("/api/watch/glance?tz=" + TZ, session.accessToken());
        assertEquals(HttpStatus.OK, watch.getStatusCode());

        JsonNode phoneStrip = json.readTree(phone.getBody()).path("data").path("watch");
        JsonNode watchStrip = json.readTree(watch.getBody()).path("watch");
        assertTrue(phoneStrip.isObject(), "phone dashboard must show a strip for a 3.2 mmol/L reading: " + phone.getBody());
        assertTrue(watchStrip.isObject(), "the glance must carry the strip");
        assertEquals("LOW_NOW", watchStrip.get("reason").asText());
        // nextCheckAt is "now + 15 min" on each call's own clock; the calls are moments apart.
        Duration apart = Duration.between(LocalDateTime.parse(phoneStrip.get("nextCheckAt").asText()),
                LocalDateTime.parse(watchStrip.get("nextCheckAt").asText())).abs();
        assertTrue(apart.compareTo(Duration.ofMinutes(1)) < 0, "nextCheckAt differs by " + apart);
        assertEquals(withoutNextCheck(phoneStrip), withoutNextCheck(watchStrip));
    }

    // -- helpers ---------------------------------------------------------------

    private record Session(String username, String accessToken) {}

    private Session signIn() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest register = new RegisterRequest();
        register.setUsername("watch_" + suffix);
        register.setEmail("watch+" + suffix + "@example.com");
        register.setFullName("Watch User");
        register.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", entity(register, null), String.class);
        AuthRequest login = new AuthRequest();
        login.setUsername(register.getUsername());
        login.setPassword(register.getPassword());
        AuthResponse body = rest.postForEntity("/api/auth/login", entity(login, null), AuthResponse.class).getBody();
        assertNotNull(body);
        return new Session(register.getUsername(), body.getAccessToken());
    }

    private JsonNode withoutNextCheck(JsonNode strip) {
        ObjectNode copy = strip.deepCopy();
        copy.remove("nextCheckAt");
        return copy;
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, entity(null, bearer), String.class);
    }

    private <T> HttpEntity<T> entity(T body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return new HttpEntity<>(body, headers);
    }
}
