package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end tests for logging an activity note (type = activity) via the notes API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {"app.features.backend-mode-enabled=true"})
@SuppressWarnings({"resource", "null"})
class NotesActivityE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        authHeaders = registerAndLogin();
    }

    @Test
    @DisplayName("a valid activity note is stored with its typed fields and returned")
    void createActivityNote_persistsTypedFields() throws Exception {
        ResponseEntity<String> resp = postNote(activity("RUNNING", "MODERATE", 40));
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(), resp.getBody());
        JsonNode body = mapper.readTree(resp.getBody());
        assertEquals("activity", body.path("type").asText());
        assertEquals("RUNNING", body.path("activityType").asText());
        assertEquals("MODERATE", body.path("intensity").asText());
        assertEquals(40, body.path("durationMin").asInt());
    }

    @Test
    @DisplayName("a non-positive duration is rejected")
    void invalidDuration_rejected() {
        assertEquals(HttpStatus.BAD_REQUEST, postNote(activity("RUNNING", "MODERATE", 0)).getStatusCode());
    }

    @Test
    @DisplayName("an unknown intensity is rejected")
    void invalidIntensity_rejected() {
        assertEquals(HttpStatus.BAD_REQUEST, postNote(activity("RUNNING", "SUPERHUMAN", 30)).getStatusCode());
    }

    @Test
    @DisplayName("an unknown activity type is rejected")
    void invalidType_rejected() {
        assertEquals(HttpStatus.BAD_REQUEST, postNote(activity("TELEPORTING", "MODERATE", 30)).getStatusCode());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private CreateNoteRequest activity(String type, String intensity, int durationMin) {
        CreateNoteRequest n = new CreateNoteRequest();
        n.setTimestamp(LocalDateTime.now().minusMinutes(30));
        n.setCarbs(0.0);
        n.setInsulin(0.0);
        n.setMeal("workout");
        n.setType("activity");
        n.setActivityType(type);
        n.setIntensity(intensity);
        n.setDurationMin(durationMin);
        return n;
    }

    private ResponseEntity<String> postNote(CreateNoteRequest note) {
        return rest.exchange("/api/notes", HttpMethod.POST,
                new HttpEntity<>(note, authHeaders), String.class);
    }

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("act_" + suffix);
        reg.setEmail("act+" + suffix + "@example.com");
        reg.setFullName("Act User");
        reg.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", jsonEntity(reg), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        ResponseEntity<AuthResponse> resp =
                rest.postForEntity("/api/auth/login", jsonEntity(login), AuthResponse.class);
        assertNotNull(resp.getBody());
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
