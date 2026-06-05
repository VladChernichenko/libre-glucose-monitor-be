package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.entity.Experiment;
import che.glucosemonitorbe.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
    "app.features.experiments-enabled=true",
    "app.features.backend-mode-enabled=true",
    "app.features.carbs-on-board-enabled=true"
})
@SuppressWarnings({"resource", "null"})
class ExperimentE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        authHeaders = registerAndLogin();
    }

    // ── T1: Start experiment → 201, status IN_PROGRESS ───────────────────────

    @Test
    @DisplayName("T1 — POST /api/experiments starts BASAL_CHECK and returns 201")
    void startBasalCheck_returns201_andStatusInProgress() {
        StartExperimentRequest req = new StartExperimentRequest(Experiment.Type.BASAL_CHECK, null, null);
        ResponseEntity<ExperimentDTO> resp = rest.exchange(
                "/api/experiments", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders), ExperimentDTO.class);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "Starting an experiment should return 201 Created");
        assertNotNull(resp.getBody());
        assertEquals(Experiment.Type.BASAL_CHECK, resp.getBody().getType(),
                "Returned type must match requested type");
        assertEquals(Experiment.Status.IN_PROGRESS, resp.getBody().getStatus(),
                "Newly started experiment must be IN_PROGRESS");
        assertNotNull(resp.getBody().getId(),   "Experiment must have an ID");
        assertNotNull(resp.getBody().getStartedAt(), "startedAt must be set");
    }

    // ── T2: Available experiments list ────────────────────────────────────────

    @Test
    @DisplayName("T2 — GET /api/experiments/available returns all 3 experiment types")
    void getAvailable_returnsAllThreeTypes() {
        ResponseEntity<List<AvailableExperimentDTO>> resp = rest.exchange(
                "/api/experiments/available", HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(3, resp.getBody().size(), "Must return all 3 experiment types");

        List<String> types = resp.getBody().stream()
                .map(e -> e.getType().name()).toList();
        assertTrue(types.contains("BASAL_CHECK"),  "BASAL_CHECK must be in the list");
        assertTrue(types.contains("CARB_FACTOR"),  "CARB_FACTOR must be in the list");
        assertTrue(types.contains("ISF_ONE_UNIT"), "ISF_ONE_UNIT must be in the list");

        // BASAL_CHECK is always available; the others are locked without a prior basal check
        AvailableExperimentDTO basal = resp.getBody().stream()
                .filter(e -> e.getType() == Experiment.Type.BASAL_CHECK).findFirst().orElseThrow();
        assertTrue(basal.isAvailable(), "BASAL_CHECK should always be available");

        AvailableExperimentDTO carb = resp.getBody().stream()
                .filter(e -> e.getType() == Experiment.Type.CARB_FACTOR).findFirst().orElseThrow();
        assertFalse(carb.isAvailable(), "CARB_FACTOR requires a completed stable basal check first");
        assertNotNull(carb.getLockReason(), "CARB_FACTOR must have a lock reason");
    }

    // ── T3: Record readings → timestamps ascending ────────────────────────────

    @Test
    @DisplayName("T3 — POST /{id}/reading records glucose; readings timestamps ascending")
    void recordReadings_appendsInOrder() throws Exception {
        UUID expId = startBasalCheckAndGetId();

        postReading(expId, 6.5, 0, "Baseline");
        Thread.sleep(50);
        postReading(expId, 6.6, 30, "T+30min");
        Thread.sleep(50);
        postReading(expId, 6.4, 60, "T+60min");

        ResponseEntity<ExperimentDTO> resp = rest.exchange(
                "/api/experiments/" + expId, HttpMethod.GET,
                new HttpEntity<>(authHeaders), ExperimentDTO.class);

        assertNotNull(resp.getBody());
        List<ExperimentReadingDTO> readings = resp.getBody().getReadings();
        assertEquals(3, readings.size(), "Should have 3 readings");
        for (int i = 1; i < readings.size(); i++) {
            assertTrue(readings.get(i).getRecordedAt().isAfter(readings.get(i - 1).getRecordedAt())
                    || readings.get(i).getRecordedAt().isEqual(readings.get(i - 1).getRecordedAt()),
                    "Readings must be in non-decreasing timestamp order at index " + i);
        }
    }

    // ── T4: Complete experiment saves result ──────────────────────────────────

    @Test
    @DisplayName("T4 — POST /{id}/complete returns result with explanation and marks COMPLETED")
    void completeBasalCheck_returnsResult_andSetsCompleted() {
        UUID expId = startBasalCheckAndGetId();
        postReading(expId, 6.2, 0,   "Baseline");
        postReading(expId, 6.5, 60,  "T+60min");
        postReading(expId, 6.3, 120, "T+120min");

        ResponseEntity<ExperimentResultDTO> result = rest.exchange(
                "/api/experiments/" + expId + "/complete", HttpMethod.POST,
                new HttpEntity<>(authHeaders), ExperimentResultDTO.class);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertNotNull(result.getBody().getIsStable(),    "BASAL_CHECK must produce isStable");
        assertNotNull(result.getBody().getExplanation(), "Result must have an explanation");
        assertNotNull(result.getBody().getExperiment());
        assertEquals(Experiment.Status.COMPLETED, result.getBody().getExperiment().getStatus());
    }

    // ── T5: Carb Factor computation ───────────────────────────────────────────

    @Test
    @DisplayName("T5 — CARB_FACTOR completion computes carbRatio = rise / grams")
    void completeCarbFactor_computesCorrectCarbRatio() {
        // First complete a stable basal check to unlock CARB_FACTOR
        completeStableBasalCheck();

        // Start carb factor experiment with 15g
        StartExperimentRequest req = new StartExperimentRequest(Experiment.Type.CARB_FACTOR, 15.0, null);
        ResponseEntity<ExperimentDTO> start = rest.exchange(
                "/api/experiments", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders), ExperimentDTO.class);
        assertEquals(HttpStatus.CREATED, start.getStatusCode());
        UUID expId = start.getBody().getId();

        // Baseline 7.0, peak 10.0 → rise = 3.0 → carbRatio = 3.0 / 15 = 0.2 mmol/L per gram
        postReading(expId, 7.0, 0,  "Baseline");
        postReading(expId, 10.0, 35, "Peak");
        postReading(expId, 9.5, 60,  "T+60min");

        ResponseEntity<ExperimentResultDTO> result = rest.exchange(
                "/api/experiments/" + expId + "/complete", HttpMethod.POST,
                new HttpEntity<>(authHeaders), ExperimentResultDTO.class);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody().getComputedCarbRatio(),
                "CARB_FACTOR must produce computedCarbRatio");
        assertEquals(0.2, result.getBody().getComputedCarbRatio(), 0.01,
                "carbRatio should be rise/grams = 3.0/15 = 0.20 mmol/L per gram");
        assertTrue(result.getBody().isSavedToSettings(),
                "Result should be automatically saved to COBSettings");
    }

    // ── T6: ISF computation ───────────────────────────────────────────────────

    @Test
    @DisplayName("T6 — ISF_ONE_UNIT completion computes isf = drop / units")
    void completeIsfOneUnit_computesCorrectIsf() {
        completeStableBasalCheck();

        StartExperimentRequest req = new StartExperimentRequest(Experiment.Type.ISF_ONE_UNIT, null, 1.0);
        ResponseEntity<ExperimentDTO> start = rest.exchange(
                "/api/experiments", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders), ExperimentDTO.class);
        assertEquals(HttpStatus.CREATED, start.getStatusCode());
        UUID expId = start.getBody().getId();

        // Baseline 13.0, nadir 10.5 → drop = 2.5 → isf = 2.5 / 1 = 2.5 mmol/L per unit
        postReading(expId, 13.0, 0,   "Baseline");
        postReading(expId, 11.5, 60,  "T+60min");
        postReading(expId, 10.5, 120, "Nadir");
        postReading(expId, 11.0, 180, "T+180min");

        ResponseEntity<ExperimentResultDTO> result = rest.exchange(
                "/api/experiments/" + expId + "/complete", HttpMethod.POST,
                new HttpEntity<>(authHeaders), ExperimentResultDTO.class);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody().getComputedIsf(), "ISF_ONE_UNIT must produce computedIsf");
        assertEquals(2.5, result.getBody().getComputedIsf(), 0.01,
                "isf should be drop/units = 2.5/1 = 2.5 mmol/L per unit");
        assertTrue(result.getBody().isSavedToSettings(),
                "ISF result should be saved to COBSettings");
    }

    // ── T7: Dirty background → 409 ───────────────────────────────────────────

    @Test
    @DisplayName("T7 — Starting experiment with active COB returns 409 Conflict")
    void startWithDirtyBackground_returns409() {
        // Post a recent note with lots of carbs to dirty the background
        postCarbNote(80.0);

        StartExperimentRequest req = new StartExperimentRequest(Experiment.Type.BASAL_CHECK, null, null);
        ResponseEntity<String> resp = rest.exchange(
                "/api/experiments", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders), String.class);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode(),
                "Starting with active COB should return 409 Conflict — got: " + resp.getBody());
    }

    // ── T8: Parallel experiment → 409 ────────────────────────────────────────

    @Test
    @DisplayName("T8 — Starting a second experiment while one is active returns 409")
    void startSecondExperiment_returns409() {
        // First experiment starts fine (clean background)
        StartExperimentRequest req = new StartExperimentRequest(Experiment.Type.BASAL_CHECK, null, null);
        ResponseEntity<ExperimentDTO> first = rest.exchange(
                "/api/experiments", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders), ExperimentDTO.class);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());

        // Second attempt should be rejected
        ResponseEntity<String> second = rest.exchange(
                "/api/experiments", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders), String.class);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode(),
                "Second experiment start should return 409 — got: " + second.getBody());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("exp_" + suffix);
        reg.setEmail("exp+" + suffix + "@example.com");
        reg.setFullName("Experiment User");
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

    private UUID startBasalCheckAndGetId() {
        StartExperimentRequest req = new StartExperimentRequest(Experiment.Type.BASAL_CHECK, null, null);
        ResponseEntity<ExperimentDTO> resp = rest.exchange(
                "/api/experiments", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders), ExperimentDTO.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        return resp.getBody().getId();
    }

    private void postReading(UUID expId, double glucose, int minutesElapsed, String label) {
        RecordReadingRequest req = new RecordReadingRequest(glucose, minutesElapsed, label);
        ResponseEntity<ExperimentDTO> resp = rest.exchange(
                "/api/experiments/" + expId + "/reading", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders), ExperimentDTO.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "Recording reading should return 200 — got: " + resp.getStatusCode());
    }

    /** Complete a stable basal check to unlock CARB_FACTOR and ISF_ONE_UNIT. */
    private void completeStableBasalCheck() {
        UUID expId = startBasalCheckAndGetId();
        // Tight readings: delta = 0.3 mmol/L → stable
        postReading(expId, 6.5, 0,   "Baseline");
        postReading(expId, 6.7, 60,  "T+60min");
        postReading(expId, 6.6, 120, "T+120min");
        postReading(expId, 6.8, 180, "T+180min");

        rest.exchange("/api/experiments/" + expId + "/complete", HttpMethod.POST,
                new HttpEntity<>(authHeaders), ExperimentResultDTO.class);
    }

    private void postCarbNote(double carbs) {
        CreateNoteRequest note = new CreateNoteRequest();
        note.setTimestamp(LocalDateTime.now().minusMinutes(15));
        note.setCarbs(carbs);
        note.setInsulin(0.0);
        note.setMeal("Test meal");
        rest.exchange("/api/notes", HttpMethod.POST,
                new HttpEntity<>(note, authHeaders), NoteDto.class);
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
