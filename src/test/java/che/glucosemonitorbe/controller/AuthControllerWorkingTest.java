package che.glucosemonitorbe.controller;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuthControllerWorkingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    private <T> HttpEntity<T> json(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private RegisterRequest validRegister() {
        RegisterRequest r = new RegisterRequest();
        r.setUsername("testuser");
        r.setEmail("test@example.com");
        r.setFullName("Test User");
        r.setPassword("testpass123");
        return r;
    }

    @Test
    @DisplayName("Register → 2xx and user persisted")
    void register_ok() {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/register", json(validRegister()), String.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertTrue(userRepository.existsByUsername("testuser"));
    }

    @Test
    @DisplayName("Login → returns tokens")
    void login_ok() {
        rest.postForEntity("/api/auth/register", json(validRegister()), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername("testuser");
        login.setPassword("testpass123");

        ResponseEntity<AuthResponse> resp =
                rest.postForEntity("/api/auth/login", json(login), AuthResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().getAccessToken());
        assertNotNull(resp.getBody().getRefreshToken());
        assertEquals("Bearer", resp.getBody().getTokenType());
        assertTrue(resp.getBody().getExpiresIn() > 0);
    }
}