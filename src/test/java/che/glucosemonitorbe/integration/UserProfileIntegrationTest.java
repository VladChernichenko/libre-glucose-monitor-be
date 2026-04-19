package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.dto.UserResponse;
import che.glucosemonitorbe.repository.UserRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class UserProfileIntegrationTest {

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
        r.setUsername("profile_" + suffix);
        r.setEmail("profile+" + suffix + "@example.com");
        r.setFullName("Profile User");
        r.setPassword("testpass123");
        return r;
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
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

    @Test
    @DisplayName("GET /api/users/me returns user profile for authenticated user")
    void getProfile_returnsUserData() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        ResponseEntity<UserResponse> resp = rest.exchange(
                "/api/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                UserResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UserResponse body = resp.getBody();
        assertNotNull(body);
        assertNotNull(body.getId());
        assertEquals(req.getUsername(), body.getUsername());
        assertEquals(req.getEmail(), body.getEmail());
        assertEquals(req.getFullName(), body.getFullName());
    }

    @Test
    @DisplayName("GET /api/users/me without token returns 401")
    void getProfile_noToken_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                "/api/users/me",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("Registered user profile has non-null id and expected username")
    void getProfile_hasExpectedFields() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        ResponseEntity<UserResponse> resp = rest.exchange(
                "/api/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                UserResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UserResponse body = resp.getBody();
        assertNotNull(body);
        assertNotNull(body.getId(), "User ID must not be null");
        assertNotNull(body.getUsername(), "Username must not be null");
        assertNotNull(body.getEmail(), "Email must not be null");
        assertTrue(body.isEnabled(), "Registered user must be enabled");
    }
}
