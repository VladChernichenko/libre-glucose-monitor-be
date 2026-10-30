package che.glucosemonitorbe.integration;

import static org.junit.jupiter.api.Assertions.*;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** POST a quick-log shorthand to the assistant, read the NDJSON stream, check the note and history. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class AssistantChatE2ETest {

    private static final String ZONE = "Europe/Berlin";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    private String login() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest r = new RegisterRequest();
        r.setUsername("chat_" + suffix);
        r.setEmail("chat+" + suffix + "@example.com");
        r.setFullName("Chat User");
        r.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", r, String.class);
        AuthRequest login = new AuthRequest();
        login.setUsername(r.getUsername());
        login.setPassword(r.getPassword());
        AuthResponse resp = rest.postForEntity("/api/auth/login", login, AuthResponse.class).getBody();
        assertNotNull(resp);
        return resp.getAccessToken();
    }

    private ResponseEntity<String> post(String token, String text, String clientTime, String timezone) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("text", text);
        form.add("clientTime", clientTime);
        form.add("timezone", timezone);
        return rest.exchange("/api/assistant/messages", HttpMethod.POST, new HttpEntity<>(form, h), String.class);
    }

    private List<JsonNode> events(String ndjson) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String line : ndjson.split("\n")) {
            if (!line.isBlank()) out.add(objectMapper.readTree(line));
        }
        return out;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private String localNow() {
        return LocalDateTime.now(ZoneId.of(ZONE)).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    @Test
    @DisplayName("POST \"5u 50g\" streams logged+done, creates the note, and history returns both turns")
    void quickLogEndToEnd() throws Exception {
        String token = login();

        ResponseEntity<String> resp = post(token, "5u 50g", localNow(), ZONE);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_NDJSON));

        List<JsonNode> events = events(resp.getBody());
        assertEquals(List.of("logged", "done"), events.stream().map(e -> e.get("type").asText()).toList());
        JsonNode logged = events.get(0);
        assertEquals(1, logged.get("noteIds").size());
        assertTrue(logged.has("iob"));
        assertTrue(logged.get("summary").asText().contains("50 g"));
        String noteId = logged.get("noteIds").get(0).asText();

        List<NoteDto> notes = rest.exchange("/api/notes", HttpMethod.GET, new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<List<NoteDto>>() {}).getBody();
        assertNotNull(notes);
        NoteDto note = notes.stream().filter(n -> n.getId().toString().equals(noteId)).findFirst().orElseThrow();
        assertEquals(50.0, note.getCarbs(), 1e-9);
        assertEquals(5.0, note.getInsulin(), 1e-9);

        ResponseEntity<String> history = rest.exchange("/api/assistant/messages?limit=50", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
        assertEquals(HttpStatus.OK, history.getStatusCode());
        JsonNode page = objectMapper.readTree(history.getBody());
        assertEquals(2, page.size());
        assertEquals("ASSISTANT", page.get(0).get("role").asText());
        assertEquals("USER", page.get(1).get("role").asText());
        assertEquals("5u 50g", page.get(1).get("text").asText());
        assertEquals(noteId, page.get(1).get("noteIds").get(0).asText());

        // undo removes the note; a second undo is a no-op 200
        String userMessageId = page.get(1).get("id").asText();
        ResponseEntity<String> undo = rest.exchange("/api/assistant/messages/" + userMessageId + "/undo",
                HttpMethod.POST, new HttpEntity<>(bearer(token)), String.class);
        assertEquals(HttpStatus.OK, undo.getStatusCode());
        assertTrue(objectMapper.readTree(undo.getBody()).get("undone").asBoolean());
        ResponseEntity<String> again = rest.exchange("/api/assistant/messages/" + userMessageId + "/undo",
                HttpMethod.POST, new HttpEntity<>(bearer(token)), String.class);
        assertEquals(HttpStatus.OK, again.getStatusCode());
        assertFalse(objectMapper.readTree(again.getBody()).get("undone").asBoolean());
        List<NoteDto> after = rest.exchange("/api/notes", HttpMethod.GET, new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<List<NoteDto>>() {}).getBody();
        assertTrue(after.stream().noneMatch(n -> n.getId().toString().equals(noteId)));
    }

    @Test
    @DisplayName("an unknown timezone is rejected with 400 before anything is written")
    void badTimezoneIs400() {
        String token = login();
        ResponseEntity<String> resp = post(token, "5u", localNow(), "Mars/Olympus");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("a malformed clientTime is rejected with 400")
    void badClientTimeIs400() {
        String token = login();
        ResponseEntity<String> resp = post(token, "5u", "yesterday-ish", ZONE);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("another user cannot undo my message")
    void undoByOtherUserIs404() throws Exception {
        String mine = login();
        post(mine, "5u", localNow(), ZONE);
        JsonNode page = objectMapper.readTree(rest.exchange("/api/assistant/messages", HttpMethod.GET,
                new HttpEntity<>(bearer(mine)), String.class).getBody());
        String userMessageId = page.get(1).get("id").asText();

        String theirs = login();
        ResponseEntity<String> undo = rest.exchange("/api/assistant/messages/" + userMessageId + "/undo",
                HttpMethod.POST, new HttpEntity<>(bearer(theirs)), String.class);
        assertEquals(HttpStatus.NOT_FOUND, undo.getStatusCode());
    }
}
