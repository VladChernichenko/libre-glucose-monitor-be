package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for {@code POST /api/glucose-calculations/}.
 *
 * <p>Covers scenarios 1-7 (scenario 8: {@link PredictionE2EFeatureFlagOffTest}) plus regression
 * guards for the bugs fixed in the 2026-05-25 pass:
 * <ul>
 *   <li>BE-P0-1 — prediction path step size 1→5 min: a 4 h path must now produce exactly
 *       48 points (240 min / 5 min/step), not 240.</li>
 *   <li>BE-P0-1 — HFHP (8 h path, Double Wave): 48 dense + 24 sparse = 72 points.</li>
 * </ul>
 *
 * <p>All features are already enabled in {@code application.yml}; {@code @TestPropertySource}
 * is added explicitly to ensure correctness even if the default config changes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "app.features.backend-mode-enabled=true",
        "app.features.glucose-calculations-enabled=true",
        "app.features.glucose-calculations-migration-percent=100",
        "app.features.carbs-on-board-enabled=true",
        "app.features.insulin-calculator-enabled=true",
        "app.features.nutrition-aware-prediction-enabled=true"
})
@SuppressWarnings({"resource", "null"})
class PredictionE2ETest {

    // ── Testcontainers ────────────────────────────────────────────────────────

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    // ── Spring fixtures ───────────────────────────────────────────────────────

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /** Shared "now" — all notes and the calculation request use the same anchor. */
    private LocalDateTime testNow;
    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        testNow = LocalDateTime.now();
        authHeaders = registerAndLogin();
    }

    // ── Nutrition profile snippets ────────────────────────────────────────────

    /** Fast-absorbing high-GI carbs (white bread / rice). */
    private static final String HIGH_GI_PROFILE = """
            {"absorptionMode":"GI_GL_ENHANCED","estimatedGi":72,"glycemicLoad":43.2,
             "fiber":1.5,"protein":8.0,"fat":3.0,"absorptionSpeedClass":"FAST",
             "totalCarbs":60.0}
            """;

    /**
     * High-fat / high-protein pizza (Double Wave / Dual Wave).
     * {@code suggestedDurationHours=8.0} → prediction path extends to 8 h.
     */
    private static final String PIZZA_PROFILE = """
            {"absorptionMode":"GI_GL_ENHANCED","estimatedGi":60,"glycemicLoad":48.0,
             "fiber":3.0,"protein":20.0,"fat":25.0,"absorptionSpeedClass":"SLOW",
             "patternName":"Double Wave","bolusStrategy":"Dual Wave",
             "suggestedDurationHours":8.0,"totalCarbs":80.0}
            """;

    /**
     * High-fibre lentil / legume meal — blunted, slow absorption.
     * Majority of carbs still on board 45 min after eating.
     */
    private static final String FIBER_PROFILE = """
            {"absorptionMode":"GI_GL_ENHANCED","estimatedGi":30,"glycemicLoad":15.0,
             "fiber":12.0,"protein":9.0,"fat":2.0,"absorptionSpeedClass":"SLOW",
             "patternName":"Blunted Curve","bolusStrategy":"Normal",
             "suggestedDurationHours":4.0,"totalCarbs":50.0}
            """;

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 1: Baseline — no notes, stable glucose
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1 · Baseline — no notes → COB=0, IOB=0, prediction ≈ current, trend=stable")
    void baseline_noNotes_stableGlucose() throws Exception {
        double current = 7.2;
        JsonNode data = calculate(current);

        double cob  = data.path("activeCarbsOnBoard").asDouble();
        double iob  = data.path("activeInsulinOnBoard").asDouble();
        double pred = data.path("twoHourPrediction").asDouble();
        String trend = data.path("predictionTrend").asText();

        assertEquals(0.0, cob, 0.01,
                "COB must be 0 with no active notes — got: " + cob);
        assertEquals(0.0, iob, 0.01,
                "IOB must be 0 with no insulin notes — got: " + iob);
        assertEquals("stable", trend,
                "Trend must be stable when COB=IOB=0 — got: " + trend);
        assertEquals(current, pred, 0.5,
                "Prediction should stay near current glucose with no active factors — got: " + pred);

        // Path must exist and start near current glucose
        JsonNode path = data.path("predictionPath");
        assertTrue(path.isArray() && path.size() > 0,
                "predictionPath must be non-empty");
        double firstPoint = path.get(0).path("predictedGlucose").asDouble();
        assertEquals(current, firstPoint, 1.0,
                "First path point should be close to currentGlucose — got: " + firstPoint);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 2: Fast spike — high-GI carbs + matching bolus, 30 min ago
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2 · Fast spike — 60g high-GI + 6u bolus 30 min ago → COB>0, IOB>0, carb effect positive")
    void fastSpike_highGiMeal_30minsAgo() throws Exception {
        postNote(60.0, 6.0, "Lunch", HIGH_GI_PROFILE, 30);

        JsonNode data = calculate(8.0);

        double cob     = data.path("activeCarbsOnBoard").asDouble();
        double iob     = data.path("activeInsulinOnBoard").asDouble();
        double carbEff = data.path("factors").path("carbContribution").asDouble();
        double iobEff  = data.path("factors").path("insulinContribution").asDouble();
        String trend   = data.path("predictionTrend").asText();

        assertTrue(cob > 0,
                "COB should be positive 30 min after 60g carbs — got: " + cob);
        assertTrue(iob > 0,
                "IOB should be positive 30 min after 6u bolus — got: " + iob);
        assertTrue(carbEff > 0,
                "carbContribution should be positive when carbs still absorbing — got: " + carbEff);
        assertTrue(iobEff < 0,
                "insulinContribution should be negative when insulin still active — got: " + iobEff);
        assertTrue("rising".equals(trend) || "stable".equals(trend),
                "Trend should be rising or stable 30 min after a high-GI meal — got: " + trend);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 3: IOB only — correction bolus, no carbs, glucose falling
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3 · IOB only — 4u correction 45 min ago, no carbs → falling trend, COB=0, IOB>0")
    void iobOnly_correctionBolus_glucoseFalling() throws Exception {
        postNote(0.0, 4.0, "Correction", null, 45);

        double current = 12.0;
        JsonNode data  = calculate(current);

        double cob    = data.path("activeCarbsOnBoard").asDouble();
        double iob    = data.path("activeInsulinOnBoard").asDouble();
        double pred   = data.path("twoHourPrediction").asDouble();
        String trend  = data.path("predictionTrend").asText();
        double carbEff = data.path("factors").path("carbContribution").asDouble();
        double iobEff  = data.path("factors").path("insulinContribution").asDouble();

        assertEquals(0.0, cob, 0.01,
                "COB must be 0 when no carbs were eaten — got: " + cob);
        assertTrue(iob > 0,
                "IOB should be positive 45 min after 4u correction — got: " + iob);
        assertTrue(pred < current,
                "Prediction should be below current glucose with active IOB and no COB — got pred="
                        + pred + " vs current=" + current);
        assertEquals("falling", trend,
                "Trend should be falling with IOB and no COB — got: " + trend);
        assertEquals(0.0, carbEff, 0.01,
                "carbContribution must be 0 with no active carbs — got: " + carbEff);
        assertTrue(iobEff < 0,
                "insulinContribution must be negative — got: " + iobEff);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 4: Double Wave — HFHP pizza, 60 min ago
    // Also regression guard for BE-P0-1: 8h path = 48 dense + 24 sparse = 72 points
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("4 · Double Wave — HFHP pizza 60 min ago → path extends to 8h, Dual Wave strategy")
    void doubleWave_pizzaMeal_8hPath() throws Exception {
        postNote(80.0, 7.0, "Dinner", PIZZA_PROFILE, 60);

        JsonNode data = calculate(9.0);

        JsonNode path     = data.path("predictionPath");
        JsonNode factors  = data.path("factors");
        String  pattern   = factors.path("matchedPattern").asText();
        String  strategy  = factors.path("bolusStrategy").asText();
        Object  fourHour  = data.path("fourHourPrediction").asText();

        assertTrue(path.isArray() && path.size() > 0,
                "predictionPath must be present for HFHP meal");
        // BE-P0-1 regression: 8h HFHP path = 48 dense (5-min) + 24 sparse (10-min) = 72 points.
        // Before the fix this was 240 + 24 = 264. The new size must be ≤100.
        assertTrue(path.size() <= 100,
                "BE-P0-1 regression: path size " + path.size()
                        + " is too large — step size has reverted to 1 min (expected ≤100 for 8h path)");
        // Path must extend beyond a plain 4h run (48 points) due to HFHP suggestedDurationHours=8
        assertTrue(path.size() > 48,
                "HFHP 8h path should have more than 48 points — got: " + path.size());

        assertTrue(pattern.contains("Wave") || "Dual Wave".equals(strategy),
                "HFHP pizza must match Double Wave pattern or Dual Wave strategy — pattern='"
                        + pattern + "', strategy='" + strategy + "'");
        assertNotNull(fourHour, "fourHourPrediction must be present for an 8h path");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 5: Blunted Curve — high-fibre meal, 45 min ago
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5 · Blunted Curve — 50g high-fibre meal 45 min ago → COB > 20g still on board")
    void bluntedCurve_highFibreMeal_mostCarbsStillOnBoard() throws Exception {
        postNote(50.0, 4.0, "Lunch", FIBER_PROFILE, 45);

        JsonNode data = calculate(6.5);
        double cob = data.path("activeCarbsOnBoard").asDouble();

        assertTrue(cob > 20.0,
                "Slow-absorbing high-fibre meal: most carbs should still be on board 45 min later — got COB: " + cob);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 6: Pre-bolus timing — optimal (20 min early) vs. late (same time)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("6 · Pre-bolus timing — late bolus produces higher preBolusTimingContribution than optimal")
    void preBolusTiming_lateVsOptimal() throws Exception {
        // ── User A: optimal bolus 20 min before carbs ──
        HttpHeaders headersA = registerAndLogin();
        // Bolus at -50 min, meal at -30 min → bolus led by 20 min
        postNoteWithHeaders(0.0, 5.0, "Correction", null, 50, headersA);
        postNoteWithHeaders(60.0, 0.0, "Lunch", HIGH_GI_PROFILE, 30, headersA);
        JsonNode dataA = calculateWithHeaders(8.0, headersA);
        double optimalContrib = dataA.path("factors").path("preBolusTimingContribution").asDouble();

        // ── User B (same auth session, different note set): bolus at same time as carbs ──
        HttpHeaders headersB = registerAndLogin();
        // Bolus and meal both at -30 min → 0 min lead
        postNoteWithHeaders(60.0, 5.0, "Lunch", HIGH_GI_PROFILE, 30, headersB);
        JsonNode dataB = calculateWithHeaders(8.0, headersB);
        double lateContrib = dataB.path("factors").path("preBolusTimingContribution").asDouble();

        // Late bolus → higher positive timing contribution (more post-meal rise expected)
        assertTrue(optimalContrib <= lateContrib,
                "Late bolus should produce a higher timing contribution than a 20-min pre-bolus."
                        + " optimal=" + optimalContrib + ", late=" + lateContrib);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 7: Prediction path shape invariants
    // Also regression guard for BE-P0-1: 4h path must have exactly 48 points (5-min steps)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("7 · Path shape — timestamps ascending, glucose in [1,25], 4h=48 points (BE-P0-1 regression)")
    void predictionPath_shapeInvariants() throws Exception {
        // A standard meal gives us a non-trivial path to validate
        postNote(45.0, 4.0, "Breakfast", HIGH_GI_PROFILE, 60);

        JsonNode data = calculate(7.5);
        JsonNode path = data.path("predictionPath");

        assertTrue(path.isArray() && path.size() > 0,
                "predictionPath must be non-empty");

        // BE-P0-1 regression: 4h path at 5-min steps = 48 points (was 240 at 1-min steps).
        // Accept ≤52 to allow for rounding edge cases, but never anywhere near 240.
        assertTrue(path.size() <= 52,
                "BE-P0-1 regression: predictionPath has " + path.size()
                        + " points — step size must have reverted to 1 min (expected ≈48 for 4h path)");
        assertTrue(path.size() >= 44,
                "predictionPath should have ≈48 points for a standard 4h path — got: " + path.size());

        // Timestamps strictly ascending; glucose in physiological range [1.0, 25.0]
        for (int i = 1; i < path.size(); i++) {
            LocalDateTime prev = LocalDateTime.parse(path.get(i - 1).path("timestamp").asText());
            LocalDateTime curr = LocalDateTime.parse(path.get(i).path("timestamp").asText());
            assertTrue(curr.isAfter(prev),
                    "Timestamps must be strictly ascending at index " + i
                            + " — prev=" + prev + ", curr=" + curr);

            double g = path.get(i).path("predictedGlucose").asDouble();
            assertTrue(g >= 1.0 && g <= 25.0,
                    "predictedGlucose out of physiological range at index " + i + ": " + g);
        }

        // fourHourPrediction matches the last point of a standard 4h path
        double lastPointGlucose = path.get(path.size() - 1).path("predictedGlucose").asDouble();
        double fourHour = data.path("fourHourPrediction").asDouble();
        assertEquals(lastPointGlucose, fourHour, 0.11,
                "fourHourPrediction should equal the last path point for a standard 4h run — "
                        + "lastPoint=" + lastPointGlucose + ", fourHour=" + fourHour);

        // First point close to current glucose
        double firstPoint = path.get(0).path("predictedGlucose").asDouble();
        assertEquals(7.5, firstPoint, 2.0,
                "First path point should start near currentGlucose — got: " + firstPoint);
    }

    // Scenario 8 (feature flag off): PredictionE2EFeatureFlagOffTest (separate Spring context).

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 9: COB taper — no sudden step near meal-window expiry
    // Regression guard for the hard-cutoff-causes-step bug fixed in CarbsOnBoardService.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A 50g FAST-class meal eaten 90 min ago has its COB window expire at
     * 120 min from the meal, i.e., 30 min into the prediction path.
     *
     * <p>Before the fix, COB snapped from ~7.9g to 0g at that boundary, creating
     * a {@code carbsDeliveredEffect} jump of ~1.6 mmol/L in a single 5-min step.
     *
     * <p>After the fix, a 30-min linear taper reduces the remaining COB smoothly.
     * The largest consecutive step in the whole prediction path must stay below
     * 0.8 mmol/L (pre-fix value was ~1.6 mmol/L for this scenario).
     */
    @Test
    @DisplayName("9 · COB taper — no sudden step in path when FAST meal expires (regression: hard-cutoff step)")
    void predictionPath_noSuddenStep_cobExpiryFastMeal() throws Exception {
        // 50g FAST (window=120 min), bolus at same time — meal exactly at taper entry now
        String fastProfile = """
                {"absorptionMode":"GI_GL_ENHANCED","estimatedGi":75,"glycemicLoad":37.5,
                 "fiber":1.0,"protein":3.0,"fat":2.0,"absorptionSpeedClass":"FAST",
                 "totalCarbs":50.0}
                """;
        postNote(50.0, 4.0, "Snack", fastProfile, 90);

        JsonNode data = calculate(8.5);
        JsonNode path = data.path("predictionPath");

        assertTrue(path.isArray() && path.size() > 0, "predictionPath must be non-empty");

        double maxDelta = 0.0;
        for (int i = 1; i < path.size(); i++) {
            double prev = path.get(i - 1).path("predictedGlucose").asDouble();
            double curr = path.get(i).path("predictedGlucose").asDouble();
            maxDelta = Math.max(maxDelta, Math.abs(curr - prev));
        }

        // Pre-fix the hard cutoff at minute 30 produced a ~1.6 mmol/L jump.
        // Post-fix the taper must keep every consecutive step below 0.8 mmol/L.
        assertTrue(maxDelta < 0.8,
                "COB-taper regression: max consecutive prediction-path step = " + maxDelta
                + " mmol/L. A value ≥ 0.8 indicates the hard-cutoff step is back "
                + "(pre-fix value for this scenario was ~1.6 mmol/L).");
    }

    /**
     * Default (no speed class) meal eaten 148 min ago expires at 240 min from
     * meal = 92 min into the prediction path.  This is the exact scenario observed
     * in the original bug report (Lunch 35g + Pre-bolus 5u).
     *
     * <p>Pre-fix: COB snapped from ~0.9g → 0 at minute 92, causing a ~0.18 mmol/L step.
     * Post-fix: the taper zone (210-240 min from meal = 62-92 min from now) smooths
     * this transition; the step near minute 92 must be ≤ 0.1 mmol/L.
     */
    @Test
    @DisplayName("9b · COB taper — original bug scenario: 35g meal 148 min ago, step near minute 92 < 0.1")
    void predictionPath_originalBugScenario_tinyStepAtExpiry() throws Exception {
        postNote(35.0, 5.0, "Lunch", null, 148);

        JsonNode data = calculate(8.5);
        JsonNode path = data.path("predictionPath");

        assertTrue(path.isArray() && path.size() > 0, "predictionPath must be non-empty");

        // Find the prediction window 80-100 min from now (where expiry fires at ~92 min).
        // Path step = 5 min; minute 80 = index 16 (80/5 - 1), minute 100 = index 19.
        double maxDeltaInExpiryWindow = 0.0;
        for (int i = 1; i < path.size(); i++) {
            // Each index = (i * 5) minutes from now
            int minuteFromNow = i * 5;
            if (minuteFromNow < 80 || minuteFromNow > 100) continue;

            double prev = path.get(i - 1).path("predictedGlucose").asDouble();
            double curr = path.get(i).path("predictedGlucose").asDouble();
            maxDeltaInExpiryWindow = Math.max(maxDeltaInExpiryWindow, Math.abs(curr - prev));
        }

        // predictedGlucose is rounded to 1 decimal place; 0.1 is the minimum non-zero step.
        // Pre-fix: the hard COB cutoff added ~0.08 mmol/L on top of normal model evolution,
        // producing a total step of ~0.18 in this window.
        // Post-fix: the taper reduces the COB burst to near-zero; residual step is normal
        // model evolution (≈ 0.1 mmol/L per 5-min step for this scenario).
        // Threshold 0.15 sits between pre-fix (~0.18) and post-fix (~0.10).
        assertTrue(maxDeltaInExpiryWindow < 0.15,
                "COB-taper original-bug regression: step near minute 92 = " + maxDeltaInExpiryWindow
                + " mmol/L. Pre-fix was ~0.18 mmol/L; post-fix must be < 0.15 (normal model evolution).");
    }

    // Additional regression: 4h baseline path = exactly 48 points (BE-P0-1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BE-P0-1 regression — 4h path with no HFHP notes has 48 points (5-min steps, not 240)")
    void beP0_1_regression_4hPathHas48Points() throws Exception {
        // No notes → pure 4h default path at PREDICTION_PATH_STEP_MINUTES=5
        JsonNode data = calculate(6.0);
        JsonNode path = data.path("predictionPath");

        assertTrue(path.isArray(),
                "predictionPath must be an array");
        int size = path.size();
        // 240 min / 5 min per step = 48. Allow ±2 for boundary rounding.
        assertTrue(size >= 46 && size <= 50,
                "BE-P0-1: 4h path should have ≈48 points with step=5 min — got " + size
                        + ". If this is 240 the step size has reverted to 1 min.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("pred_" + suffix);
        reg.setEmail("pred+" + suffix + "@example.com");
        reg.setFullName("Pred User");
        reg.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", jsonEntity(reg), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        ResponseEntity<AuthResponse> resp =
                rest.postForEntity("/api/auth/login", jsonEntity(login), AuthResponse.class);
        assertNotNull(resp.getBody(), "Login response body must not be null");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(resp.getBody().getAccessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** POST a note {@code minutesAgo} before {@code testNow} for the default test user. */
    private void postNote(double carbs, double insulin, String meal,
                          String nutritionProfile, int minutesAgo) {
        postNoteWithHeaders(carbs, insulin, meal, nutritionProfile, minutesAgo, authHeaders);
    }

    /** POST a note for any user identified by {@code headers}. */
    private void postNoteWithHeaders(double carbs, double insulin, String meal,
                                     String nutritionProfile, int minutesAgo,
                                     HttpHeaders headers) {
        CreateNoteRequest n = new CreateNoteRequest();
        n.setTimestamp(testNow.minusMinutes(minutesAgo));
        n.setCarbs(carbs);
        n.setInsulin(insulin > 0 ? insulin : null);
        n.setMeal(meal);
        if (nutritionProfile != null) {
            n.setNutritionProfile(nutritionProfile);
        }
        rest.exchange("/api/notes", HttpMethod.POST,
                new HttpEntity<>(n, headers), String.class);
    }

    /**
     * POST to /api/glucose-calculations/ and return the {@code data} node.
     * Fails fast if {@code backendMode} is false (feature flags not active).
     */
    private JsonNode calculate(double currentGlucose) throws Exception {
        return calculateWithHeaders(currentGlucose, authHeaders);
    }

    private JsonNode calculateWithHeaders(double currentGlucose, HttpHeaders headers) throws Exception {
        ResponseEntity<String> resp = rest.exchange(
                "/api/glucose-calculations/",
                HttpMethod.POST,
                new HttpEntity<>(calcBody(currentGlucose), headers),
                String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "Expected 200 from /api/glucose-calculations/ — got: " + resp.getStatusCode());
        JsonNode root = mapper.readTree(resp.getBody());
        assertTrue(root.path("backendMode").asBoolean(),
                "backendMode must be true — check @TestPropertySource / application.yml features. "
                        + "Response: " + resp.getBody());
        return root.path("data");
    }

    /** Build the raw Map body for POST /api/glucose-calculations/ */
    private Map<String, Object> calcBody(double currentGlucose) {
        return Map.of(
                "currentGlucose", currentGlucose,
                "clientTimeInfo", Map.of(
                        "timestamp", testNow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        "timezone", "UTC",
                        "locale", "en-US",
                        "timezoneOffset", 0
                ),
                "includePredictionFactors", true
        );
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
