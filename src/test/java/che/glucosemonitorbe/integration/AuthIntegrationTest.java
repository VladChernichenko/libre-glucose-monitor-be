package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.LogoutRequest;
import che.glucosemonitorbe.dto.LogoutResponse;
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
class AuthIntegrationTest {

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

    private <T> HttpEntity<T> json(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private RegisterRequest validRegister() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest r = new RegisterRequest();
        r.setUsername("user_" + suffix);
        r.setEmail("user+" + suffix + "@example.com");
        r.setFullName("Test User");
        r.setPassword("testpass123");
        return r;
    }

    private String loginAndGetToken(RegisterRequest register) {
        rest.postForEntity("/api/auth/register", json(register), String.class);
        AuthRequest login = new AuthRequest();
        login.setUsername(register.getUsername());
        login.setPassword(register.getPassword());
        ResponseEntity<AuthResponse> resp = rest.postForEntity("/api/auth/login", json(login), AuthResponse.class);
        assertNotNull(resp.getBody());
        return resp.getBody().getAccessToken();
    }

    @Test
    @DisplayName("Register returns 200 and persists user")
    void register_createsUser() {
        RegisterRequest req = validRegister();
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/register", json(req), String.class);

        assertTrue(resp.getStatusCode().is2xxSuccessful(), "Expected 2xx but got: " + resp.getStatusCode());
        assertTrue(userRepository.existsByUsername(req.getUsername()));
    }

    @Test
    @DisplayName("Login returns access and refresh tokens")
    void login_returnsTokens() {
        RegisterRequest req = validRegister();
        rest.postForEntity("/api/auth/register", json(req), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(req.getUsername());
        login.setPassword("testpass123");

        ResponseEntity<AuthResponse> resp = rest.postForEntity("/api/auth/login", json(login), AuthResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        AuthResponse body = resp.getBody();
        assertNotNull(body);
        assertNotNull(body.getAccessToken());
        assertNotNull(body.getRefreshToken());
        assertEquals("Bearer", body.getTokenType());
        assertTrue(body.getExpiresIn() > 0);
    }

    @Test
    @DisplayName("Logout blacklists the token; subsequent use gets 401")
    void logout_blacklistsToken() {
        RegisterRequest req = validRegister();
        String token = loginAndGetToken(req);

        // Logout
        LogoutRequest logoutReq = new LogoutRequest();
        logoutReq.setAccessToken(token);
        ResponseEntity<LogoutResponse> logoutResp =
                rest.postForEntity("/api/auth/logout", json(logoutReq), LogoutResponse.class);
        assertTrue(logoutResp.getStatusCode().is2xxSuccessful());

        // Using the blacklisted token should be rejected
        HttpHeaders authed = new HttpHeaders();
        authed.setBearerAuth(token);
        authed.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> protectedCall =
                rest.exchange("/api/users/me", HttpMethod.GET, new HttpEntity<>(authed), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, protectedCall.getStatusCode());
    }

    @Test
    @DisplayName("Wrong password returns 4xx")
    void login_wrongPassword_rejected() {
        RegisterRequest req = validRegister();
        rest.postForEntity("/api/auth/register", json(req), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(req.getUsername());
        login.setPassword("wrongpassword");

        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login", json(login), String.class);
        assertTrue(resp.getStatusCode().is4xxClientError(), "Expected 4xx but got: " + resp.getStatusCode());
    }
}
