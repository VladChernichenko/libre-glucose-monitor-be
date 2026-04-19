package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.NoteDto;
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
import org.springframework.core.ParameterizedTypeReference;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class NotesIntegrationTest {

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
        r.setUsername("notes_" + suffix);
        r.setEmail("notes+" + suffix + "@example.com");
        r.setFullName("Notes User");
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

    @Test
    @DisplayName("Create a note returns 201 with note data")
    void createNote_returns201WithNote() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        CreateNoteRequest noteReq = new CreateNoteRequest();
        noteReq.setTimestamp(LocalDateTime.now());
        noteReq.setCarbs(45.0);
        noteReq.setInsulin(4.0);
        noteReq.setMeal("Lunch");
        noteReq.setComment("Integration test note");

        ResponseEntity<NoteDto> resp = rest.exchange(
                "/api/notes",
                HttpMethod.POST,
                new HttpEntity<>(noteReq, headers),
                NoteDto.class);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        NoteDto body = resp.getBody();
        assertNotNull(body);
        assertNotNull(body.getId());
        assertEquals("Lunch", body.getMeal());
    }

    @Test
    @DisplayName("Get all notes returns list containing the created note")
    void getNotes_returnsCreatedNote() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        // Create a note first
        CreateNoteRequest noteReq = new CreateNoteRequest();
        noteReq.setTimestamp(LocalDateTime.now());
        noteReq.setCarbs(30.0);
        noteReq.setMeal("Breakfast");
        rest.exchange("/api/notes", HttpMethod.POST, new HttpEntity<>(noteReq, headers), NoteDto.class);

        // Now fetch all notes
        ResponseEntity<List<NoteDto>> resp = rest.exchange(
                "/api/notes",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<NoteDto>>() {});

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<NoteDto> notes = resp.getBody();
        assertNotNull(notes);
        assertFalse(notes.isEmpty(), "Expected at least one note");
        assertTrue(notes.stream().anyMatch(n -> "Breakfast".equals(n.getMeal())));
    }

    @Test
    @DisplayName("Delete a note returns 204 and note is gone")
    void deleteNote_removesNote() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        // Create a note
        CreateNoteRequest noteReq = new CreateNoteRequest();
        noteReq.setTimestamp(LocalDateTime.now());
        noteReq.setCarbs(20.0);
        noteReq.setMeal("Snack");
        ResponseEntity<NoteDto> created = rest.exchange(
                "/api/notes",
                HttpMethod.POST,
                new HttpEntity<>(noteReq, headers),
                NoteDto.class);
        assertNotNull(created.getBody());
        UUID noteId = created.getBody().getId();

        // Delete it
        ResponseEntity<Void> deleteResp = rest.exchange(
                "/api/notes/" + noteId,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResp.getStatusCode());

        // Verify it's gone
        ResponseEntity<NoteDto> getResp = rest.exchange(
                "/api/notes/" + noteId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                NoteDto.class);
        assertEquals(HttpStatus.NOT_FOUND, getResp.getStatusCode());
    }
}
