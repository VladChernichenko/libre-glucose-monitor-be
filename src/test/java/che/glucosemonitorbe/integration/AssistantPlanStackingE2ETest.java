package che.glucosemonitorbe.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshot;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotRepository;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.service.nutrition.ARNutritionService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Insulin stacking and snapshot scoring around a meal plan: any other rapid dose supersedes a PLANNED
 * plan (so its now-stale dose cannot be Took), the plan's own Took does not, and the dose-only
 * snapshot of a pre-bolus is excluded once the meal is eaten (kept when the meal is skipped).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class AssistantPlanStackingE2ETest {

    private static final String ZONE = "UTC";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @MockitoBean private LlmGatewayService llm;
    @MockitoBean private ARNutritionService arNutrition;

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private CgmReadingRepository cgmReadingRepository;
    @Autowired private UserSettingsService userSettingsService;
    @Autowired private ForecastSnapshotRepository snapshots;
    @Autowired private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        String username = "stack_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setEmail(username + "@example.com");
        r.setFullName("Stack User");
        r.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", r, String.class);
        AuthRequest login = new AuthRequest();
        login.setUsername(username);
        login.setPassword("testpass123");
        token = rest.postForEntity("/api/auth/login", login, AuthResponse.class).getBody().getAccessToken();
        UUID userId = userRepository.findByUsername(username).orElseThrow().getId();

        UserSettingsDTO s = new UserSettingsDTO();
        s.setIsf(2.5);
        s.setCarbRatio(1.5);
        s.setTargetGlucoseMmol(5.5);
        userSettingsService.saveUserSettings(userId, s);

        long now = Instant.now().toEpochMilli();
        List<CgmReading> batch = new ArrayList<>();
        for (int i = 12; i >= 0; i--) {
            long t = now - i * 5 * 60_000L;
            batch.add(CgmReading.builder().userId(userId).dataSource(CgmReading.DataSource.NIGHTSCOUT)
                    .externalId("s" + t).sgv(144).dateTimestamp(t).lastUpdated(LocalDateTime.now()).build());
        }
        cgmReadingRepository.saveAll(batch);

        when(llm.completeJson(anyString())).thenReturn(
                "{\"intent\":\"MEAL_PREP\",\"items\":[{\"name\":\"rye bread\",\"grams\":40}]}");
        when(llm.streamPromptReply(anyString(), anyString(), any())).thenAnswer(inv -> {
            Consumer<String> c = inv.getArgument(2);
            c.accept("ok");
            return LlmGatewayService.GatewayResult.builder().modelId("test-model").rawOutput("x").build();
        });
        when(arNutrition.analyze(any(ARNutritionRequest.class))).thenReturn(NutritionSnapshot.builder()
                .source("AR_OFF").confidence(0.75).totalCarbs(45.0).fat(8.0).protein(20.0).fiber(4.0)
                .estimatedGi(50.0).patternName("Slow Climb").build());
    }

    private HttpHeaders bearer() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static LocalDateTime localNow() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private List<JsonNode> send(String text) throws Exception {
        HttpHeaders h = bearer();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("text", text);
        form.add("clientTime", localNow().toString());
        form.add("timezone", ZONE);
        ResponseEntity<String> resp = rest.exchange("/api/assistant/messages", HttpMethod.POST,
                new HttpEntity<>(form, h), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<JsonNode> out = new ArrayList<>();
        for (String line : resp.getBody().split("\n")) if (!line.isBlank()) out.add(objectMapper.readTree(line));
        return out;
    }

    private String plan() throws Exception {
        List<JsonNode> events = send("prepare me for 40 g rye bread");
        assertEquals("plan", events.get(0).get("type").asText());
        return events.get(0).get("messageId").asText();
    }

    private ResponseEntity<String> action(String messageId, String verb, Map<String, Object> extra) {
        HttpHeaders h = bearer();
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new java.util.HashMap<>(extra);
        body.put("clientTime", localNow().toString());
        body.put("timezone", ZONE);
        return rest.exchange("/api/assistant/plans/" + messageId + "/" + verb, HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<String> took(String messageId) {
        return action(messageId, "took", Map.of("doseUnits", 2.0, "pauseMinutes", 0));
    }

    private JsonNode planState(String messageId) throws Exception {
        JsonNode page = objectMapper.readTree(rest.exchange("/api/assistant/messages", HttpMethod.GET,
                new HttpEntity<>(bearer()), String.class).getBody());
        for (JsonNode m : page) if (m.get("id").asText().equals(messageId)) return m.get("payload");
        return fail("plan message not in history: " + messageId);
    }

    private List<NoteDto> notes() {
        return rest.exchange("/api/notes", HttpMethod.GET, new HttpEntity<>(bearer()),
                new ParameterizedTypeReference<List<NoteDto>>() {}).getBody();
    }

    private ForecastSnapshot awaitSnapshot(UUID noteId, Predicate<ForecastSnapshot> done) throws Exception {
        for (int i = 0; i < 150; i++) {
            var s = snapshots.findByNoteId(noteId);
            if (s.isPresent() && done.test(s.get())) return s.get();
            Thread.sleep(100);
        }
        return fail("snapshot for note " + noteId + " never reached the expected state");
    }

    @Test
    @DisplayName("quick log \"3u\" supersedes the PLANNED plan; Took on it is then 409 and writes nothing")
    void quickLogDoseSupersedesPlannedPlan() throws Exception {
        String a = plan();

        List<JsonNode> logged = send("3u");
        assertTrue(logged.stream().anyMatch(e -> e.get("type").asText().equals("done")), logged.toString());
        List<NoteDto> afterLog = notes();
        assertEquals(1, afterLog.size());
        assertEquals(3.0, afterLog.get(0).getInsulin(), 1e-9);

        JsonNode p = planState(a);
        assertEquals("SUPERSEDED", p.get("state").asText());
        assertEquals(afterLog.get(0).getId().toString(), p.get("supersededByNoteId").asText());
        assertEquals(HttpStatus.CONFLICT, took(a).getStatusCode());
        assertEquals(1, notes().size(), "no second dose was written");
    }

    @Test
    @DisplayName("Took's own dose does not supersede its plan; Eating then excludes the dose-only snapshot")
    void tookOwnDoseKeepsPlanAndEatingExcludesDoseSnapshot() throws Exception {
        String b = plan();

        ResponseEntity<String> tookResp = took(b);
        assertEquals(HttpStatus.OK, tookResp.getStatusCode(), tookResp.getBody());
        JsonNode tookPayload = planState(b);
        assertEquals("TOOK", tookPayload.get("state").asText());
        UUID doseNote = UUID.fromString(tookPayload.get("doseNoteId").asText());

        ResponseEntity<String> eat = action(b, "eating", Map.of());
        assertEquals(HttpStatus.OK, eat.getStatusCode(), eat.getBody());
        ForecastSnapshot dose = awaitSnapshot(doseNote, ForecastSnapshot::isExcluded);
        assertTrue(dose.isExcluded());
    }

    @Test
    @DisplayName("Took then Skip keeps the dose snapshot scored")
    void skipKeepsDoseSnapshot() throws Exception {
        String c = plan();
        assertEquals(HttpStatus.OK, took(c).getStatusCode());
        UUID doseNote = UUID.fromString(planState(c).get("doseNoteId").asText());

        assertEquals(HttpStatus.OK, action(c, "skip", Map.of()).getStatusCode());
        // Freeze and any exclusion run in order on one thread; once frozen, nothing else is queued.
        ForecastSnapshot dose = awaitSnapshot(doseNote, s -> true);
        Thread.sleep(300);
        assertFalse(snapshots.findByNoteId(dose.getNoteId()).orElseThrow().isExcluded());
    }
}
