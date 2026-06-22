package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.repository.UserRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    @DisplayName("Refresh token rotates once; reusing the old refresh token afterwards is rejected")
    void refresh_reuseOfOldToken_rejected() {
        RegisterRequest req = validRegister();
        AuthRequest login = new AuthRequest();
        login.setUsername(req.getUsername());
        login.setPassword(req.getPassword());
        rest.postForEntity("/api/auth/register", json(req), String.class);
        AuthResponse loginResp = rest.postForEntity("/api/auth/login", json(login), AuthResponse.class).getBody();
        assertNotNull(loginResp);
        String oldRefreshToken = loginResp.getRefreshToken();

        RefreshTokenRequest refreshReq = new RefreshTokenRequest();
        refreshReq.setRefreshToken(oldRefreshToken);
        ResponseEntity<AuthResponse> first = rest.postForEntity("/api/auth/refresh", json(refreshReq), AuthResponse.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertNotNull(first.getBody());
        assertNotEquals(oldRefreshToken, first.getBody().getRefreshToken());

        // Reusing the now-rotated old refresh token must be rejected, not silently re-accepted.
        ResponseEntity<String> reuse = rest.postForEntity("/api/auth/refresh", json(refreshReq), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, reuse.getStatusCode());
    }

    @Test
    @DisplayName("Concurrent refresh with the same refresh token: exactly one request wins, the rest are rejected (BE token-refresh race fix)")
    void refresh_concurrentSameToken_onlyOneWinner() throws InterruptedException {
        RegisterRequest req = validRegister();
        AuthRequest login = new AuthRequest();
        login.setUsername(req.getUsername());
        login.setPassword(req.getPassword());
        rest.postForEntity("/api/auth/register", json(req), String.class);
        AuthResponse loginResp = rest.postForEntity("/api/auth/login", json(login), AuthResponse.class).getBody();
        assertNotNull(loginResp);
        String sharedRefreshToken = loginResp.getRefreshToken();

        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<HttpStatusCode>> futures = IntStream.range(0, concurrency)
                    .mapToObj(i -> pool.submit(() -> {
                        RefreshTokenRequest refreshReq = new RefreshTokenRequest();
                        refreshReq.setRefreshToken(sharedRefreshToken);
                        startGate.await();
                        return rest.postForEntity("/api/auth/refresh", json(refreshReq), String.class).getStatusCode();
                    }))
                    .collect(Collectors.toList());

            startGate.countDown();
            List<HttpStatusCode> results = futures.stream().map(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());

            long winners = results.stream().filter(s -> s == HttpStatus.OK).count();
            long rejections = results.stream().filter(s -> s == HttpStatus.UNAUTHORIZED).count();

            // Before the fix, the blacklist check-then-write race let multiple concurrent
            // requests all pass the "not blacklisted" check and each mint a valid token pair
            // from the same refresh token. Exactly one request must win the rotation now.
            assertEquals(1, winners, "Exactly one concurrent refresh should succeed, got results: " + results);
            assertEquals(concurrency - 1, rejections, "All other concurrent refreshes should be rejected as revoked, got results: " + results);
        } finally {
            pool.shutdownNow();
        }
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
