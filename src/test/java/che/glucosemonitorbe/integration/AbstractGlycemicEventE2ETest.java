package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.*;
import che.glucosemonitorbe.service.GlycemicEventScanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared fixture for the glycemic-event E2E suites. One Postgres container for the whole JVM (singleton
 * pattern) so every subclass shares one cached Spring context. Users default to UTC, so
 * {@link #toLdt(long)} converts CGM epochs to the same wall clock the engine uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "app.features.glycemic-event-detection-enabled=true",
    "app.features.digital-twin-enabled=true",
    "app.features.activity-logging-enabled=true",
    "app.glucose-sync.enabled=false",
    "app.glycemic-events.initial-delay-ms=3600000"   // keep the scheduler out of the tests
})
@SuppressWarnings({"resource", "null"})
abstract class AbstractGlycemicEventE2ETest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    static { POSTGRES.start(); }

    protected static final long STEP = 5 * 60 * 1000L;

    @Autowired protected TestRestTemplate rest;
    @Autowired protected UserRepository userRepository;
    @Autowired protected CgmReadingRepository cgmReadingRepository;
    @Autowired protected NoteRepository noteRepository;
    @Autowired protected GlycemicEventRepository eventRepository;
    @Autowired protected GlycemicEventHistoryRepository historyRepository;
    @Autowired protected GlycemicEventScanService scanService;
    protected final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    protected HttpHeaders authHeaders;

    @BeforeEach
    void resetUsers() {
        userRepository.deleteAll();
        authHeaders = registerAndLogin();
    }

    protected static long nowMs() { return Instant.now().toEpochMilli(); }

    protected static LocalDateTime toLdt(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    protected static long ms(LocalDateTime ldt) { return ldt.toInstant(ZoneOffset.UTC).toEpochMilli(); }

    protected static int[] repeat(int n, int v) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    protected static int[] concat(int[]... parts) {
        return java.util.Arrays.stream(parts).flatMapToInt(java.util.Arrays::stream).toArray();
    }

    /** One reading every 5 minutes from {@code startMs}; reading i is at {@code startMs + i * STEP}. */
    protected void seed(UUID userId, long startMs, int... sgvMgdl) {
        List<CgmReading> batch = new ArrayList<>();
        for (int i = 0; i < sgvMgdl.length; i++) {
            batch.add(CgmReading.builder().userId(userId).dataSource(CgmReading.DataSource.NIGHTSCOUT)
                    .externalId("g" + startMs + "_" + i).sgv(sgvMgdl[i]).dateTimestamp(startMs + i * STEP)
                    .lastUpdated(LocalDateTime.now()).build());
        }
        cgmReadingRepository.saveAll(batch);
    }

    protected Note note(UUID userId, LocalDateTime ts, double carbs, double insulin, String type) {
        Note n = new Note();
        n.setUserId(userId);
        n.setTimestamp(ts);
        n.setCarbs(carbs);
        n.setInsulin(insulin);
        n.setMeal("test");
        n.setType(type);
        n.setCreatedAt(LocalDateTime.now());
        n.setUpdatedAt(LocalDateTime.now());
        return noteRepository.save(n);
    }

    protected UUID userId() { return userRepository.findAll().get(0).getId(); }

    protected HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("gly_" + suffix);
        reg.setEmail("gly+" + suffix + "@example.com");
        reg.setFullName("Glycemic User");
        reg.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", jsonEntity(reg), String.class);
        AuthRequest login = new AuthRequest();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        ResponseEntity<AuthResponse> resp = rest.postForEntity("/api/auth/login", jsonEntity(login), AuthResponse.class);
        assertNotNull(resp.getBody());
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(resp.getBody().getAccessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
