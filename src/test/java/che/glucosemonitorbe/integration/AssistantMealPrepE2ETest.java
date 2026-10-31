package che.glucosemonitorbe.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotRepository;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
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
 * Meal prep end to end with a mocked LLM and nutrition: the plan's dose is the calculator's, its
 * curve is the dashboard forecast, Took saves only the dose, Eating now saves the meal (and freezes
 * its forecast), and a free-text event is saved only on Confirm.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class AssistantMealPrepE2ETest {

    private static final String ZONE = "UTC"; // the registered user's stored zone, so snapshots are not "backdated"
    private static final double GLUCOSE_MGDL = 144; // 8.0 mmol/L
    private static final double TARGET = 5.5;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @MockitoBean private LlmGatewayService llm;
    @MockitoBean private ARNutritionService arNutrition;

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private CgmReadingRepository cgmReadingRepository;
    @Autowired private UserSettingsService userSettingsService;
    @Autowired private InsulinCalculatorService insulinCalculator;
    @Autowired private GlucoseCalculationsService calculations;
    @Autowired private ForecastSnapshotRepository snapshots;
    @Autowired private ObjectMapper objectMapper;

    private String token;
    private UUID userId;
    private String username;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        username = "meal_" + suffix;
        token = login(username);
        userId = userRepository.findByUsername(username).orElseThrow().getId();

        UserSettingsDTO s = new UserSettingsDTO();
        s.setIsf(2.5);
        s.setCarbRatio(1.5);
        s.setTargetGlucoseMmol(TARGET);
        userSettingsService.saveUserSettings(userId, s);

        long now = Instant.now().toEpochMilli();
        List<CgmReading> batch = new ArrayList<>();
        for (int i = 12; i >= 0; i--) {
            long t = now - i * 5 * 60_000L;
            batch.add(CgmReading.builder().userId(userId).dataSource(CgmReading.DataSource.NIGHTSCOUT)
                    .externalId("m" + t).sgv((int) GLUCOSE_MGDL).dateTimestamp(t)
                    .lastUpdated(LocalDateTime.now()).build());
        }
        cgmReadingRepository.saveAll(batch);

        when(llm.streamPromptReply(anyString(), anyString(), any())).thenAnswer(inv -> {
            Consumer<String> c = inv.getArgument(2);
            c.accept("Bread is most of the carbs.");
            return LlmGatewayService.GatewayResult.builder().modelId("test-model").rawOutput("x").build();
        });
        when(arNutrition.analyze(any(ARNutritionRequest.class))).thenReturn(NutritionSnapshot.builder()
                .source("AR_OFF").confidence(0.75).totalCarbs(45.0).fat(8.0).protein(20.0).fiber(4.0)
                .estimatedGi(50.0).patternName("Slow Climb").build());
    }

    private String login(String user) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(user);
        r.setEmail(user + "@example.com");
        r.setFullName("Meal User");
        r.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", r, String.class);
        AuthRequest login = new AuthRequest();
        login.setUsername(user);
        login.setPassword("testpass123");
        AuthResponse resp = rest.postForEntity("/api/auth/login", login, AuthResponse.class).getBody();
        assertNotNull(resp);
        return resp.getAccessToken();
    }

    private HttpHeaders bearer(String t) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t);
        return h;
    }

    private static LocalDateTime localNow() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private List<JsonNode> send(String text, LocalDateTime clientNow) throws Exception {
        HttpHeaders h = bearer(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("text", text);
        form.add("clientTime", clientNow.toString());
        form.add("timezone", ZONE);
        ResponseEntity<String> resp = rest.exchange("/api/assistant/messages", HttpMethod.POST,
                new HttpEntity<>(form, h), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<JsonNode> out = new ArrayList<>();
        for (String line : resp.getBody().split("\n")) {
            if (!line.isBlank()) out.add(objectMapper.readTree(line));
        }
        return out;
    }

    private ResponseEntity<String> action(String t, String path, Map<String, Object> body) {
        HttpHeaders h = bearer(t);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private List<NoteDto> notes() {
        return rest.exchange("/api/notes", HttpMethod.GET, new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<List<NoteDto>>() {}).getBody();
    }

    private void awaitSnapshot(UUID noteId) throws Exception {
        for (int i = 0; i < 150; i++) {
            if (snapshots.findByNoteId(noteId).isPresent()) return;
            Thread.sleep(100);
        }
        fail("no forecast snapshot was frozen for the meal note");
    }

    @Test
    @DisplayName("prepare me for chicken soup with 40 g rye bread: plan = calculator dose + dashboard curve; Took, then Eating now")
    void mealPrepEndToEnd() throws Exception {
        when(llm.completeJson(anyString())).thenReturn("{\"intent\":\"MEAL_PREP\",\"items\":["
                + "{\"name\":\"chicken soup\"},{\"name\":\"rye bread\",\"grams\":40}],\"tags\":[]}");
        LocalDateTime clientNow = localNow();

        List<JsonNode> events = send("prepare me for chicken soup with 40 g rye bread", clientNow);
        List<String> types = events.stream().map(e -> e.get("type").asText()).toList();
        assertEquals("plan", types.get(0), types.toString());
        assertTrue(types.contains("token"));
        assertEquals("done", types.get(types.size() - 1));
        JsonNode planEvent = events.get(0);
        String messageId = planEvent.get("messageId").asText();
        JsonNode plan = planEvent.get("plan");
        assertFalse(plan.get("stale").asBoolean());
        assertEquals(45.0, plan.get("carbs").get("mid").asDouble(), 1e-9);

        // dose = the calculator for the same inputs, rounded down to 0.5 U
        double raw = insulinCalculator.calculateRecommendedInsulin(InsulinCalculationRequest.builder()
                .carbs(45.0).currentGlucose(GLUCOSE_MGDL / 18.0).targetGlucose(TARGET).activeInsulin(0.0)
                .userId(userId.toString())
                .clientTimeInfo(ClientTimeInfo.builder().timestamp(clientNow.toString()).build())
                .build()).getRecommendedInsulin();
        double dose = Math.floor(Math.max(0, raw) / 0.5 + 1e-9) * 0.5;
        assertTrue(dose > 0, "test setup should produce a dose");
        assertEquals(dose, plan.get("doseUnits").asDouble(), 1e-9);

        // curve = the dashboard forecast with the dose now and the meal after the chosen pause
        JsonNode pause = plan.get("pause");
        int pauseMinutes = pause.get("pauseMinutes").isNull() ? 0 : pause.get("pauseMinutes").asInt();
        String nutritionJson = objectMapper.writeValueAsString(NutritionSnapshot.builder()
                .totalCarbs(45.0).fat(8.0).protein(20.0).fiber(4.0).estimatedGi(50.0).build());
        List<PredictionPointDTO> dashboard = calculations.calculateGlucoseData(GlucoseCalculationsRequest.builder()
                .userId(username)
                .currentGlucose(GLUCOSE_MGDL / 18.0)
                .clientTimeInfo(ClientTimeInfo.builder().timestamp(clientNow.toString()).build())
                .prospectiveNotes(List.of(
                        ProspectiveNoteDTO.builder().insulin(dose).minutesAgo(0).build(),
                        ProspectiveNoteDTO.builder().carbs(45.0).nutritionProfileJson(nutritionJson)
                                .minutesAgo(-pauseMinutes).build()))
                .predictionPathMinutes(270)
                .build()).getPredictionPath();
        LocalDateTime until = clientNow.plusMinutes(pauseMinutes + 240);
        List<PredictionPointDTO> expected = dashboard.stream()
                .filter(p -> p.getTimestamp() == null || !p.getTimestamp().isAfter(until)).toList();
        JsonNode curve = pause.get("curve");
        assertEquals(expected.size(), curve.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).getTimestamp(), LocalDateTime.parse(curve.get(i).get("timestamp").asText()));
            assertEquals(expected.get(i).getPredictedGlucose(), curve.get(i).get("predictedGlucose").asDouble(), 1e-9);
        }
        assertTrue(notes().isEmpty(), "planning writes no note");

        // Took it: dose note only, at the tap moment
        Map<String, Object> took = Map.of("doseUnits", dose, "pauseMinutes", 15,
                "clientTime", clientNow.toString(), "timezone", ZONE);
        ResponseEntity<String> tookResp = action(token, "/api/assistant/plans/" + messageId + "/took", took);
        assertEquals(HttpStatus.OK, tookResp.getStatusCode(), tookResp.getBody());
        JsonNode tookBody = objectMapper.readTree(tookResp.getBody());
        assertEquals("TOOK", tookBody.get("state").asText());
        assertEquals(clientNow.toString(), tookBody.get("payload").get("tookAt").asText());
        List<NoteDto> afterTook = notes();
        assertEquals(1, afterTook.size());
        assertEquals(dose, afterTook.get(0).getInsulin(), 1e-9);
        assertEquals(0.0, afterTook.get(0).getCarbs(), 1e-9);
        assertEquals(clientNow, afterTook.get(0).getTimestamp());

        // a second Took is a conflict, and another user cannot act on my plan
        assertEquals(HttpStatus.CONFLICT,
                action(token, "/api/assistant/plans/" + messageId + "/took", took).getStatusCode());
        String other = login("other_" + UUID.randomUUID().toString().substring(0, 8));
        assertEquals(HttpStatus.NOT_FOUND,
                action(other, "/api/assistant/plans/" + messageId + "/eating",
                        Map.of("clientTime", clientNow.toString(), "timezone", ZONE)).getStatusCode());

        // Eating now with edited carbs: the meal note at the tap time, and its forecast is frozen
        LocalDateTime eatAt = localNow();
        ResponseEntity<String> eatResp = action(token, "/api/assistant/plans/" + messageId + "/eating",
                Map.of("carbs", 50.0, "clientTime", eatAt.toString(), "timezone", ZONE));
        assertEquals(HttpStatus.OK, eatResp.getStatusCode(), eatResp.getBody());
        JsonNode eatBody = objectMapper.readTree(eatResp.getBody());
        assertEquals("EATEN", eatBody.get("state").asText());
        UUID mealNoteId = UUID.fromString(eatBody.get("noteId").asText());
        NoteDto meal = notes().stream().filter(n -> n.getId().equals(mealNoteId)).findFirst().orElseThrow();
        assertEquals(50.0, meal.getCarbs(), 1e-9);
        assertEquals(0.0, meal.getInsulin(), 1e-9);
        assertEquals(eatAt, meal.getTimestamp());
        JsonNode profile = objectMapper.readTree(meal.getNutritionProfile());
        assertEquals(8.0, profile.get("fat").asDouble(), 1e-9, "unedited macros come from the plan");
        awaitSnapshot(mealNoteId);

        // history carries the state for the card
        JsonNode page = objectMapper.readTree(rest.exchange("/api/assistant/messages", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getBody());
        JsonNode planMsg = null;
        for (JsonNode m : page) if (m.get("id").asText().equals(messageId)) planMsg = m;
        assertNotNull(planMsg);
        assertEquals("meal_plan", planMsg.get("intent").asText());
        assertEquals("EATEN", planMsg.get("payload").get("state").asText());
        assertEquals("Bread is most of the carbs.", planMsg.get("text").asText());
    }

    @Test
    @DisplayName("a free-text event is proposed with tags and saved only on Confirm")
    void eventProposalConfirm() throws Exception {
        when(llm.completeJson(anyString())).thenReturn("{\"intent\":\"EVENT\",\"items\":[],\"tags\":[\"HOT_SHOWER\",\"NOPE\"]}");
        LocalDateTime clientNow = localNow();

        List<JsonNode> events = send("just had a hot shower", clientNow);
        assertEquals(List.of("proposal", "done"), events.stream().map(e -> e.get("type").asText()).toList());
        JsonNode proposal = events.get(0);
        assertEquals("[\"HOT_SHOWER\"]", proposal.get("tags").toString());
        assertTrue(notes().isEmpty(), "nothing is saved before Confirm");

        String messageId = proposal.get("messageId").asText();
        Map<String, Object> body = Map.of("clientTime", clientNow.toString(), "timezone", ZONE);
        ResponseEntity<String> confirm = action(token, "/api/assistant/events/" + messageId + "/confirm", body);
        assertEquals(HttpStatus.OK, confirm.getStatusCode(), confirm.getBody());
        List<NoteDto> notes = notes();
        assertEquals(1, notes.size());
        assertEquals("[\"HOT_SHOWER\"]", objectMapper.readTree(notes.get(0).getContextTags()).toString());
        assertEquals(clientNow, notes.get(0).getTimestamp());
        assertEquals(HttpStatus.CONFLICT,
                action(token, "/api/assistant/events/" + messageId + "/confirm", body).getStatusCode());
    }
}
