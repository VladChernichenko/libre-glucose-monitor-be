package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.entity.UnloggedEventFlag;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.UnloggedEventDetectionService;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the feature flag: with detection disabled, the scanner is a no-op and the API is 404. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
    "app.features.backend-mode-enabled=true",
    "app.features.unlogged-event-detection-enabled=false"
})
@SuppressWarnings({"resource", "null"})
class UnloggedEventFeatureFlagOffE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private CgmReadingRepository cgmReadingRepository;
    @Autowired private UnloggedEventDetectionService detectionService;

    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        authHeaders = registerAndLogin();
    }

    @Test
    @DisplayName("with the feature off, scanUser is a no-op even over a clear unexplained rise")
    void scanIsNoOp_whenFeatureDisabled() {
        UUID userId = userRepository.findAll().get(0).getId();
        long now = Instant.now().toEpochMilli();
        long step = 5 * 60 * 1000L;
        List<CgmReading> batch = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            int sgv = i < 24 ? 110 : 200;
            batch.add(CgmReading.builder().userId(userId)
                    .dataSource(CgmReading.DataSource.NIGHTSCOUT).externalId("u" + i)
                    .sgv(sgv).dateTimestamp(now - (35 - i) * step).lastUpdated(LocalDateTime.now()).build());
        }
        cgmReadingRepository.saveAll(batch);

        Optional<UnloggedEventFlag> flag = detectionService.scanUser(userId);
        assertTrue(flag.isEmpty(), "no flag should be produced when the feature is disabled");
    }

    @Test
    @DisplayName("with the feature off, the API returns 404")
    void api404_whenFeatureDisabled() {
        ResponseEntity<String> resp = rest.exchange("/api/unlogged-events", HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // -- helpers ---------------------------------------------------------------

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("unlogoff_" + suffix);
        reg.setEmail("unlogoff+" + suffix + "@example.com");
        reg.setFullName("Unlog Off");
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
