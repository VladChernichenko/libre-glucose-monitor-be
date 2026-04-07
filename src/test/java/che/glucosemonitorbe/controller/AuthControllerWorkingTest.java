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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
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
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest r = new RegisterRequest();
        r.setUsername("testuser_" + suffix);
        r.setEmail("test+" + suffix + "@example.com");
        r.setFullName("Test User");
        r.setPassword("testpass123");
        return r;
    }

    @Test
    @DisplayName("Register в†’ 2xx and user persisted")
    void register_ok() {
        RegisterRequest request = validRegister();
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/register", json(request), String.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "register response: " + resp.getStatusCode() + " body=" + resp.getBody());
        assertTrue(userRepository.existsByUsername(request.getUsername()));
    }

    @Test
    @DisplayName("Login в†’ returns tokens")
    void login_ok() {
        RegisterRequest request = validRegister();
        ResponseEntity<String> reg = rest.postForEntity("/api/auth/register", json(request), String.class);
        assertTrue(reg.getStatusCode().is2xxSuccessful(), "register response: " + reg.getStatusCode() + " body=" + reg.getBody());

        AuthRequest login = new AuthRequest();
        login.setUsername(request.getUsername());
        login.setPassword("testpass123");

        ResponseEntity<AuthResponse> resp =
                rest.postForEntity("/api/auth/login", json(login), AuthResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        AuthResponse body = resp.getBody();
        assertNotNull(body);
        assertNotNull(body.getAccessToken());
        assertNotNull(body.getRefreshToken());
        assertEquals("Bearer", body.getTokenType());
        assertTrue(body.getExpiresIn() > 0);
    }
}