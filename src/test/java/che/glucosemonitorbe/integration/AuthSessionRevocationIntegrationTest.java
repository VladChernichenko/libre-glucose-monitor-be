package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.LogoutRequest;
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
 * Session revocation and continuity with the default refresh reuse grace window:
 * logout-all must stay in force after the next login, a just-rotated refresh token must not sign a
 * legitimate user out, and a refresh token revoked by logout must get no grace at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class AuthSessionRevocationIntegrationTest {

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
    @DisplayName("Logout-all stays in force after the user logs in again: older tokens are not revived")
    void logoutAll_thenLogin_doesNotReviveOlderTokens() {
        AuthRequest login = registerUser();
        AuthResponse stolen = login(login);

        assertEquals(HttpStatus.OK, post("/api/auth/logout-all", null, stolen.getAccessToken()).getStatusCode());
        AuthResponse fresh = login(login);

        assertEquals(HttpStatus.UNAUTHORIZED, refresh(stolen.getRefreshToken()).getStatusCode(),
                "refresh token issued before logout-all must stay revoked after re-login");
        assertEquals(HttpStatus.UNAUTHORIZED, me(stolen.getAccessToken()).getStatusCode(),
                "access token issued before logout-all must stay revoked after re-login");

        assertEquals(HttpStatus.OK, me(fresh.getAccessToken()).getStatusCode(),
                "tokens from the login after logout-all must work");
        assertEquals(HttpStatus.OK, refresh(fresh.getRefreshToken()).getStatusCode());
    }

    @Test
    @DisplayName("A refresh token rotated moments ago is accepted again (lost response), with a new pair")
    void refresh_reuseRightAfterRotation_isTolerated() {
        AuthResponse session = login(registerUser());

        ResponseEntity<AuthResponse> first = refresh(session.getRefreshToken());
        assertEquals(HttpStatus.OK, first.getStatusCode());

        ResponseEntity<AuthResponse> retry = refresh(session.getRefreshToken());
        assertEquals(HttpStatus.OK, retry.getStatusCode(),
                "retrying a refresh whose response was lost must not sign the user out");
        assertNotNull(retry.getBody());
        assertNotEquals(session.getRefreshToken(), retry.getBody().getRefreshToken());
        assertEquals(HttpStatus.OK, me(retry.getBody().getAccessToken()).getStatusCode());
    }

    @Test
    @DisplayName("A refresh token revoked by logout gets no grace, even immediately")
    void refresh_afterLogout_isRejectedImmediately() {
        AuthResponse session = login(registerUser());

        LogoutRequest logout = new LogoutRequest();
        logout.setAccessToken(session.getAccessToken());
        logout.setRefreshToken(session.getRefreshToken());
        assertEquals(HttpStatus.OK, post("/api/auth/logout", logout, null).getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, refresh(session.getRefreshToken()).getStatusCode());
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
