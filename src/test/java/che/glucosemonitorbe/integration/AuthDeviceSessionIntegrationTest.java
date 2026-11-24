package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RefreshTokenRequest;
import che.glucosemonitorbe.dto.RegisterRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Apple Watch gets its own session from the phone: a second token pair for the same user whose
 * refresh token rotates independently, so phone and watch never sign each other out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class AuthDeviceSessionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("A device session needs a signed-in caller")
    void deviceSession_withoutToken_is401() {
        assertEquals(HttpStatus.UNAUTHORIZED, post("/api/auth/device-session", null, null).getStatusCode());
    }

    @Test
    @DisplayName("A device session is a new working token pair, not the caller's")
    void deviceSession_returnsNewIndependentPair() {
        AuthResponse phone = login(registerUser());

        ResponseEntity<AuthResponse> response = deviceSession(phone.getAccessToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AuthResponse watch = response.getBody();
        assertNotNull(watch);
        assertNotEquals(phone.getRefreshToken(), watch.getRefreshToken());
        assertNotEquals(phone.getAccessToken(), watch.getAccessToken());
        assertEquals("Bearer", watch.getTokenType());
        assertEquals(HttpStatus.OK, me(watch.getAccessToken()).getStatusCode());
    }

    @Test
    @DisplayName("Phone and watch refresh tokens rotate independently")
    void deviceSession_chainsRotateIndependently() {
        AuthResponse phone = login(registerUser());
        AuthResponse watch = deviceSession(phone.getAccessToken()).getBody();
        assertNotNull(watch);

        AuthResponse phone2 = refresh(phone.getRefreshToken()).getBody();
        AuthResponse watch2 = refresh(watch.getRefreshToken()).getBody();
        assertNotNull(phone2);
        assertNotNull(watch2);

        assertEquals(HttpStatus.OK, refresh(phone2.getRefreshToken()).getStatusCode());
        assertEquals(HttpStatus.OK, refresh(watch2.getRefreshToken()).getStatusCode());
    }

    @Test
    @DisplayName("Log out everywhere also ends the watch's session")
    void deviceSession_isRevokedByLogoutAll() {
        AuthResponse phone = login(registerUser());
        AuthResponse watch = deviceSession(phone.getAccessToken()).getBody();
        assertNotNull(watch);

        assertEquals(HttpStatus.OK, post("/api/auth/logout-all", null, phone.getAccessToken()).getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, refresh(watch.getRefreshToken()).getStatusCode());
    }

    // -- helpers ---------------------------------------------------------------

    private AuthRequest registerUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest register = new RegisterRequest();
        register.setUsername("user_" + suffix);
        register.setEmail("user+" + suffix + "@example.com");
        register.setFullName("Test User");
        register.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", json(register, null), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(register.getUsername());
        login.setPassword(register.getPassword());
        return login;
    }

    private AuthResponse login(AuthRequest login) {
        AuthResponse body = rest.postForEntity("/api/auth/login", json(login, null), AuthResponse.class).getBody();
        assertNotNull(body);
        return body;
    }

    private ResponseEntity<AuthResponse> deviceSession(String accessToken) {
        return rest.postForEntity("/api/auth/device-session", json(null, accessToken), AuthResponse.class);
    }

    private ResponseEntity<AuthResponse> refresh(String refreshToken) {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(refreshToken);
        return rest.postForEntity("/api/auth/refresh", json(request, null), AuthResponse.class);
    }

    private ResponseEntity<String> me(String accessToken) {
        return rest.exchange("/api/users/me", HttpMethod.GET, json(null, accessToken), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, String accessToken) {
        return rest.postForEntity(path, json(body, accessToken), String.class);
    }

    private <T> HttpEntity<T> json(T body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return new HttpEntity<>(body, headers);
    }
}
