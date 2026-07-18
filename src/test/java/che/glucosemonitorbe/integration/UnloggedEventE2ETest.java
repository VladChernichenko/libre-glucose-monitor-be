package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.UnloggedEventFlag;
import che.glucosemonitorbe.entity.UnloggedEventFlag.Category;
import che.glucosemonitorbe.entity.UnloggedEventFlag.State;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UnloggedEventFlagRepository;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.service.DigitalTwinCalibrationService;
import che.glucosemonitorbe.service.UnloggedEventDetectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * End-to-end tests for the unlogged-event detector: detection + classification + dedupe + the
 * confirm/dismiss API. Seeds a synthetic unexplained rise (elevated CGM with no logged carbs) and
 * asserts the model residual flags it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
    "app.features.backend-mode-enabled=true",
    "app.features.unlogged-event-detection-enabled=true",
    "app.features.digital-twin-enabled=true",   // for the calibration-exclusion test
    "app.features.activity-logging-enabled=true",// for the activity-aware detection test
    "app.glucose-sync.enabled=false"             // keep sync schedulers quiet during the test
})
@SuppressWarnings({"resource", "null"})
class UnloggedEventE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private CgmReadingRepository cgmReadingRepository;
    @Autowired private NoteRepository noteRepository;
    @Autowired private UnloggedEventFlagRepository flagRepository;
    @Autowired private UnloggedEventDetectionService detectionService;
    @Autowired private DigitalTwinCalibrationService calibrationService;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final long STEP = 5 * 60 * 1000L;
    private static final int N = 36;                 // 175 min of 5-min readings
    private static final int ELEVATED_FROM = 24;     // last 12 readings (60 min) elevated
    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        authHeaders = registerAndLogin();
    }

    // ── T1: unexplained rise, nothing logged → UNLOGGED_FOOD ──────────────────

    @Test
    @DisplayName("T1 — a sustained rise with no logged carbs opens an UNLOGGED_FOOD flag")
    void unexplainedRise_flagsUnloggedFood() {
        UUID userId = userId();
        seedRiseWindow(userId);

        Optional<UnloggedEventFlag> flag = detectionService.scanUser(userId);

        assertTrue(flag.isPresent(), "a flag should be opened");
        assertEquals(Category.UNLOGGED_FOOD, flag.get().getCategory());
        assertEquals(UnloggedEventFlag.Direction.RISE, flag.get().getDirection());
        assertEquals(State.OPEN, flag.get().getState());
        assertTrue(flag.get().getMeanResidualMmol() > 0, "residual is a rise");
    }

    // ── T2: same rise WITH a too-small carb note → UNDER_ESTIMATED_FOOD ────────

    @Test
    @DisplayName("T2 — a rise with a too-small logged carb note opens UNDER_ESTIMATED_FOOD")
    void underEstimatedFood_whenCarbsLoggedButInsufficient() {
        UUID userId = userId();
        long elevatedStart = seedRiseWindow(userId);
        // A small carb log inside the window — present but nowhere near enough to explain the rise.
        Note n = new Note();
        n.setUserId(userId);
        n.setTimestamp(toLdt(elevatedStart));
        n.setCarbs(10.0);
        n.setInsulin(0.0);
        n.setMeal("snack");
        n.setType(Note.TYPE_NORMAL);
        n.setCreatedAt(LocalDateTime.now());
        n.setUpdatedAt(LocalDateTime.now());
        noteRepository.save(n);

        Optional<UnloggedEventFlag> flag = detectionService.scanUser(userId);

        assertTrue(flag.isPresent());
        assertEquals(Category.UNDER_ESTIMATED_FOOD, flag.get().getCategory());
    }

    // ── T3: re-scan updates the same flag, no duplicate ───────────────────────

    @Test
    @DisplayName("T3 — re-scanning the same window updates the OPEN flag rather than duplicating")
    void rescan_dedupesOpenFlag() {
        UUID userId = userId();
        seedRiseWindow(userId);

        detectionService.scanUser(userId);
        detectionService.scanUser(userId);

        assertEquals(1, flagRepository.findByUserIdOrderByDetectedAtDesc(userId).size());
    }

    // ── T4: a transient spike shorter than the persistence minimum → no flag ──

    @Test
    @DisplayName("T4 — a transient spike (< persistence minimum) produces no flag")
    void transientSpike_notFlagged() {
        UUID userId = userId();
        long now = Instant.now().toEpochMilli();
        long start = now - (N - 1) * STEP;
        List<CgmReading> batch = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            int sgv = (i == 30 || i == 31) ? 200 : 110;   // only 10 min elevated
            batch.add(reading(userId, start + i * STEP, sgv, "u" + i));
        }
        cgmReadingRepository.saveAll(batch);

        assertTrue(detectionService.scanUser(userId).isEmpty());
    }

    // ── T5: confirm with backfill via API → CONFIRMED + a note is created ─────

    @Test
    @DisplayName("T5 — confirming with a backfill amount marks CONFIRMED and creates a note")
    void confirmWithBackfill_createsNote() throws Exception {
        UUID userId = userId();
        seedRiseWindow(userId);
        UUID flagId = detectionService.scanUser(userId).orElseThrow().getId();

        ResponseEntity<String> resp = rest.exchange(
                "/api/unlogged-events/" + flagId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(new ConfirmUnloggedEventRequest(45.0, null), authHeaders), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode body = mapper.readTree(resp.getBody());
        assertEquals("CONFIRMED", body.path("state").asText());
        assertTrue(noteRepository.findAll().stream()
                        .anyMatch(nt -> nt.getCarbs() != null && nt.getCarbs() == 45.0),
                "a backfilled note with the confirmed carbs should exist");
    }

    // ── T6: dismiss via API → DISMISSED ───────────────────────────────────────

    @Test
    @DisplayName("T6 — dismissing a flag marks it DISMISSED")
    void dismiss_marksDismissed() throws Exception {
        UUID userId = userId();
        seedRiseWindow(userId);
        UUID flagId = detectionService.scanUser(userId).orElseThrow().getId();

        ResponseEntity<String> resp = rest.exchange(
                "/api/unlogged-events/" + flagId + "/dismiss", HttpMethod.POST,
                new HttpEntity<>(authHeaders), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(State.DISMISSED,
                flagRepository.findById(flagId).orElseThrow().getState());
    }

    // ── T7/T8: sustained fall → insulin categories ────────────────────────────

    @Test
    @DisplayName("T7 — a sustained fall with no logged bolus opens UNLOGGED_INSULIN")
    void unexplainedFall_flagsUnloggedInsulin() {
        UUID userId = userId();
        seedFallWindow(userId);

        Optional<UnloggedEventFlag> flag = detectionService.scanUser(userId);

        assertTrue(flag.isPresent());
        assertEquals(Category.UNLOGGED_INSULIN, flag.get().getCategory());
        assertEquals(UnloggedEventFlag.Direction.FALL, flag.get().getDirection());
        assertTrue(flag.get().getMeanResidualMmol() < 0, "residual is a fall");
    }

    @Test
    @DisplayName("T8 — a fall with a too-small logged bolus opens UNDER_ESTIMATED_INSULIN")
    void underEstimatedInsulin_whenBolusLoggedButInsufficient() {
        UUID userId = userId();
        long dropStart = seedFallWindow(userId);
        Note n = new Note();
        n.setUserId(userId);
        n.setTimestamp(toLdt(dropStart));
        n.setCarbs(0.0);
        n.setInsulin(0.5);   // present but nowhere near enough to explain the drop
        n.setMeal("correction");
        n.setType(Note.TYPE_NORMAL);
        n.setCreatedAt(LocalDateTime.now());
        n.setUpdatedAt(LocalDateTime.now());
        noteRepository.save(n);

        Optional<UnloggedEventFlag> flag = detectionService.scanUser(userId);

        assertTrue(flag.isPresent());
        assertEquals(Category.UNDER_ESTIMATED_INSULIN, flag.get().getCategory());
    }

    // ── T9: authorization + idempotency of confirm/dismiss ────────────────────

    @Test
    @DisplayName("T9a — a user cannot resolve another user's flag (404)")
    void cannotResolveOtherUsersFlag() {
        UUID userId = userId();
        seedRiseWindow(userId);
        UUID flagId = detectionService.scanUser(userId).orElseThrow().getId();

        HttpHeaders otherUser = registerAndLogin();   // a second, unrelated user
        ResponseEntity<String> resp = rest.exchange(
                "/api/unlogged-events/" + flagId + "/dismiss", HttpMethod.POST,
                new HttpEntity<>(otherUser), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    @DisplayName("T9b — resolving an already-resolved flag is rejected (409)")
    void cannotResolveAlreadyResolvedFlag() {
        UUID userId = userId();
        seedRiseWindow(userId);
        UUID flagId = detectionService.scanUser(userId).orElseThrow().getId();

        rest.exchange("/api/unlogged-events/" + flagId + "/dismiss", HttpMethod.POST,
                new HttpEntity<>(authHeaders), String.class);
        ResponseEntity<String> second = rest.exchange(
                "/api/unlogged-events/" + flagId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(new ConfirmUnloggedEventRequest(30.0, null), authHeaders), String.class);

        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
    }

    // ── T10/T11: list endpoint + confirm without a backfill amount ────────────

    @Test
    @DisplayName("T10 — GET lists the user's flags and honors the state filter")
    void listFlags_filtersByState() throws Exception {
        UUID userId = userId();
        seedRiseWindow(userId);
        detectionService.scanUser(userId);

        ResponseEntity<String> all = rest.exchange("/api/unlogged-events", HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class);
        assertEquals(HttpStatus.OK, all.getStatusCode());
        assertTrue(mapper.readTree(all.getBody()).size() >= 1);

        ResponseEntity<String> dismissed = rest.exchange("/api/unlogged-events?state=DISMISSED",
                HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertEquals(0, mapper.readTree(dismissed.getBody()).size(), "no DISMISSED flags yet");
    }

    @Test
    @DisplayName("T11 — confirming without an amount marks CONFIRMED and creates no note")
    void confirmWithoutAmount_noNote() throws Exception {
        UUID userId = userId();
        seedRiseWindow(userId);
        UUID flagId = detectionService.scanUser(userId).orElseThrow().getId();

        ResponseEntity<String> resp = rest.exchange(
                "/api/unlogged-events/" + flagId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(authHeaders), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("CONFIRMED", mapper.readTree(resp.getBody()).path("state").asText());
        assertEquals(0, noteRepository.count(), "no backfill note when no amount is supplied");
    }

    // ── T12: calibration down-weights CONFIRMED windows, keeps DISMISSED ──────

    @Test
    @DisplayName("T12 — calibration excludes CONFIRMED-window readings and keeps DISMISSED ones")
    void calibrationExcludesConfirmedKeepsDismissed() {
        UUID userId = userId();
        seedCalibrationData(userId);

        DigitalTwinCalibrator.Result r0 = calibrationService.calibrateUser(userId);
        assertNotNull(r0, "enough data to calibrate");
        int base = r0.trainSamples();
        assertTrue(base > 0);

        // A CONFIRMED flag over a 4-hour window inside the record → those readings are excluded.
        long now = Instant.now().toEpochMilli();
        LocalDateTime wEnd = toLdt(now - 6 * 3600_000L);
        LocalDateTime wStart = wEnd.minusHours(4);
        UnloggedEventFlag flag = flagRepository.save(UnloggedEventFlag.builder()
                .userId(userId).category(Category.UNLOGGED_FOOD)
                .direction(UnloggedEventFlag.Direction.RISE)
                .windowStart(wStart).windowEnd(wEnd)
                .meanResidualMmol(5.0).sigmaMultiple(3.0)
                .state(State.CONFIRMED).updatedAt(LocalDateTime.now()).build());

        int confirmed = calibrationService.calibrateUser(userId).trainSamples();
        assertTrue(confirmed < base, "CONFIRMED window readings must be excluded (got "
                + confirmed + " vs base " + base + ")");

        // Dismissing the same flag returns the readings to the fit.
        flag.setState(State.DISMISSED);
        flagRepository.save(flag);
        int dismissed = calibrationService.calibrateUser(userId).trainSamples();
        assertEquals(base, dismissed, "DISMISSED window must keep full weight");
    }

    // ── T13: a logged activity explains an exercise-driven drop → no false flag ───

    @Test
    @DisplayName("T13 — logging an activity over a drop shrinks the flagged residual (detector is activity-aware)")
    void loggedActivity_reducesFlaggedResidual() {
        UUID userId = userId();
        long dropStart = seedFallWindow(userId);   // sustained fall, no bolus

        // Without any activity, the drop is unexplained → flagged with a large negative residual.
        UnloggedEventFlag before = detectionService.scanUser(userId).orElseThrow();
        assertEquals(Category.UNLOGGED_INSULIN, before.getCategory());
        double residualBefore = Math.abs(before.getMeanResidualMmol());

        // Log a hard activity spanning the drop; a re-scan updates the same flag with the
        // activity-aware residual, which must be smaller (the exercise now explains part of the drop).
        Note act = new Note();
        act.setUserId(userId);
        act.setType(Note.TYPE_ACTIVITY);
        act.setActivityType("RUNNING");
        act.setIntensity("VERY_HARD");
        act.setDurationMin(120);
        act.setCarbs(0.0);
        act.setInsulin(0.0);
        act.setMeal("run");
        act.setTimestamp(toLdt(dropStart).minusMinutes(30));
        act.setCreatedAt(LocalDateTime.now());
        act.setUpdatedAt(LocalDateTime.now());
        noteRepository.save(act);

        detectionService.scanUser(userId);
        double residualAfter = Math.abs(flagRepository.findById(before.getId()).orElseThrow()
                .getMeanResidualMmol());
        assertTrue(residualAfter < residualBefore,
                "activity should reduce the unexplained residual (before=" + residualBefore
                        + ", after=" + residualAfter + ")");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Seed a flat baseline then a sustained elevated tail with no logged inputs. Returns elevated-start ms. */
    private long seedRiseWindow(UUID userId) {
        long now = Instant.now().toEpochMilli();
        long start = now - (N - 1) * STEP;
        List<CgmReading> batch = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            int sgv = i < ELEVATED_FROM ? 110 : 200;    // 6.1 → 11.1 mmol/L
            batch.add(reading(userId, start + i * STEP, sgv, "u" + i));
        }
        cgmReadingRepository.saveAll(batch);
        return start + ELEVATED_FROM * STEP;
    }

    /** Seed an elevated baseline then a sustained drop with no logged inputs. Returns drop-start ms. */
    private long seedFallWindow(UUID userId) {
        long now = Instant.now().toEpochMilli();
        long start = now - (N - 1) * STEP;
        List<CgmReading> batch = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            int sgv = i < ELEVATED_FROM ? 200 : 110;    // 11.1 → 6.1 mmol/L
            batch.add(reading(userId, start + i * STEP, sgv, "u" + i));
        }
        cgmReadingRepository.saveAll(batch);
        return start + ELEVATED_FROM * STEP;
    }

    /** Seed >200 readings over ~21 h plus one meal, so calibrateUser has enough data to fit. */
    private void seedCalibrationData(UUID userId) {
        long now = Instant.now().toEpochMilli();
        int count = 260;
        long start = now - (count - 1) * STEP;
        List<CgmReading> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int sgv = 110 + ((i % 12) < 6 ? 0 : 20);    // gentle variation so residuals aren't degenerate
            batch.add(reading(userId, start + i * STEP, sgv, "c" + i));
        }
        cgmReadingRepository.saveAll(batch);
        Note meal = new Note();
        meal.setUserId(userId);
        meal.setTimestamp(toLdt(now - 12 * 3600_000L));
        meal.setCarbs(40.0);
        meal.setInsulin(3.0);
        meal.setMeal("lunch");
        meal.setType(Note.TYPE_NORMAL);
        meal.setCreatedAt(LocalDateTime.now());
        meal.setUpdatedAt(LocalDateTime.now());
        noteRepository.save(meal);
    }

    private CgmReading reading(UUID userId, long epochMs, int sgv, String externalId) {
        return CgmReading.builder()
                .userId(userId)
                .dataSource(CgmReading.DataSource.NIGHTSCOUT)
                .externalId(externalId)
                .sgv(sgv)
                .dateTimestamp(epochMs)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private UUID userId() {
        return userRepository.findAll().get(0).getId();
    }

    private static LocalDateTime toLdt(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), java.time.ZoneOffset.UTC);
    }

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("unlog_" + suffix);
        reg.setEmail("unlog+" + suffix + "@example.com");
        reg.setFullName("Unlog User");
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
