package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.integration.MealEpisode.HistoryNote;
import che.glucosemonitorbe.integration.MealEpisode.Nutrition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end check of the full prediction flow against REAL meal episodes.
 *
 * <p>Each episode is one {@code @Test} method (run any of them on its own from the IDE gutter).
 * Add a new one by copying an example and filling in what really happened; the builder is
 * {@link MealEpisode}. All minutes are relative to the FIRST BITE of the meal (t0); glucose is mmol/L:</p>
 * <pre>
 *   history notes ...... bolus (t0 - preBolusMinutes) ...... meal (t0) ...... +2h ...... +4h
 * </pre>
 *
 * <p>Per episode a fresh user goes through the real HTTP flow: register -> save settings ->
 * save insulin preferences -> log history notes -> (optional) check starting IOB/COB -> log
 * bolus + meal -> {@code POST /api/glucose-calculations/} at t0 (Hovorka model on). The predicted
 * glucose at t0+2 h / t0+4 h is read from the prediction path and compared with the actual CGM
 * value within the tolerance. With no actual values the prediction is only printed, not asserted.</p>
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
        "app.features.insulin-calculator-migration-percent=100",
        "app.features.nutrition-aware-prediction-enabled=true",
        "app.features.hovorka-model-enabled=true"
})
@SuppressWarnings({"resource", "null"})
class MealEpisodeE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired private TestRestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    // =========================================================================
    // Episodes - one @Test per real-life meal. Copy the example and fill it in.
    // =========================================================================

    @Test
    @DisplayName("Example - lunch, pasta with chicken (placeholder values - replace with real data)")
    void example_lunchPastaWithChicken() throws Exception {
        run(new MealEpisode("Lunch - pasta with chicken")
                // Local clock time of the first bite -> picks the meal-window ISF.
                .mealTime("13:00")
                // CGM at t0 and (optional) CGM trend arrow in mmol/L per minute.
                .startGlucose(7.2)
                .trendMmolPerMin(null)

                // User settings (any other UserSettingsDTO field via .setting("name", value)).
                // NB carbRatio = mmol/L rise per 10 g carbs, NOT grams per unit.
                .isf(2.5)                 // default ISF (mmol/L per U) when no window override
                .isfBreakfast(null)       // 05:00-10:59 override
                .isfLunch(null)           // 11:00-15:59 override
                .isfDinner(null)          // 16:00-21:59 override
                .isfNight(null)           // 22:00-04:59 override
                .carbRatio(1.5)
                .bodyWeightKg(70)
                .carbHalfLife(45)
                .maxCobDuration(240)

                // Insulins: FIASP / APIDRA (rapid), TRESIBA / LANTUS (long-acting).
                .insulin("FIASP", "TRESIBA", "22:00")

                // Everything logged BEFORE this meal (this is how starting IOB/COB/basal
                // are expressed - the backend derives them from notes).
                .historyMeal(150, 20, null)          // 20 g snack 150 min before the meal
                .historyBolus(150, 2.0)              // 2 U with it
                .historyLongActing(900, 18)          // 18 U Tresiba 15 h before

                // Optional: IOB (U) / COB (g) the app showed at t0 before logging this meal.
                .expectedAtStart(null, null)

                // The meal. Nutrition(gi, fiber g, protein g, fat g, speed FAST|MEDIUM|SLOW|null).
                .meal("Pasta 150 g cooked + chicken breast 120 g", 60,
                        new Nutrition(55, 3, 35, 12, "MEDIUM"))

                // Bolus and pre-bolus pause (15 = injected 15 min before eating,
                // 0 = at the meal, -10 = 10 min after starting to eat). Either the real dose:
                .bolus(6.0, 15)
                // ...or let the app's calculator (/api/insulin/calculate) pick it for target BG:
                // .bolusFromCalculator(6.0, 15)

                // What the CGM really showed at t0+2h / t0+4h (null = print only, no assert).
                .actual(null, null)
                .toleranceMmol(2.0));
    }

    @Test
    @DisplayName("Example - dinner, salad first + split bolus (placeholder values - replace with real data)")
    void example_dinnerSaladFirstSplitBolus() throws Exception {
        run(new MealEpisode("Dinner - salad first, then steak with potatoes")
                .mealTime("19:00")
                .startGlucose(8.5)
                .isf(2.5).isfDinner(null)
                .carbRatio(1.5)
                .bodyWeightKg(70)
                .insulin("FIASP", "TRESIBA", "22:00")
                .historyLongActing(1260, 18)

                // "Vegetables / protein first": salad 10 min before the main carbs.
                .firstCourse(10, "Greek salad", 6, new Nutrition(15, 5, 6, 15, "SLOW"))

                // Main carbs at t0 - a big protein/fat portion -> delayed rise expected.
                .meal("Steak 250 g + potatoes 200 g", 40, new Nutrition(78, 4, 60, 30, "MEDIUM"))

                // Split bolus: carbs dose injected with the salad, second part for protein/fat.
                .bolus(3.0, 10)
                .secondBolus(2.0, 90)

                .actual(null, null)
                .toleranceMmol(2.0));
    }

    // =========================================================================
    // Episode runner
    // =========================================================================

    private void run(MealEpisode ep) throws Exception {
        assertNotNull(ep.startGlucose, "startGlucose is required");
        LocalDateTime t0 = firstBite(ep.mealTime);
        HttpHeaders auth = registerAndLogin();

        saveSettings(ep.settings, auth);
        if (!ep.insulinPreferences.isEmpty()) saveInsulinPreferences(ep.insulinPreferences, auth);
        for (HistoryNote h : ep.history) {
            postNote(h.carbs(), h.insulin(), h.meal(), h.type(), h.nutrition(),
                    t0.minusMinutes(h.minutesBeforeMeal()), auth);
        }

        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder("\n=== ").append(ep.name).append(" ===\n");
        appendUserSettings(report, t0, auth);
        for (HistoryNote h : ep.history) {
            if (!h.firstCourse()) continue;
            Nutrition n = h.nutrition();
            report.append(String.format("first   : %s %d min before, %.0f g carbs%s%n", h.meal(),
                    h.minutesBeforeMeal(), h.carbs(), n == null ? ""
                            : String.format(", fiber %.0f g, protein %.0f g, fat %.0f g", n.fiber(), n.protein(), n.fat())));
        }

        // Starting state (before this meal is logged) - IOB/COB derived from history only.
        JsonNode start = calculate(ep.startGlucose, null, t0, List.of(), auth);
        double startIob = start.path("activeInsulinOnBoard").asDouble();
        double startCob = start.path("activeCarbsOnBoard").asDouble();
        report.append(String.format("start   : BG %.1f  IOB %.2f U  COB %.1f g%n", ep.startGlucose, startIob, startCob));
        checkWithin(failures, "start IOB", startIob, ep.expectedIob, ep.iobTolerance);
        checkWithin(failures, "start COB", startCob, ep.expectedCob, ep.cobTolerance);

        // Bolus (with pre-bolus pause) + meal.
        LocalDateTime bolusAt = t0.minusMinutes(ep.preBolusMinutes);
        if (ep.calculatorTargetGlucose != null) {
            ep.bolusUnits = recommendedDose(ep, startIob, bolusAt, auth);
            report.append(String.format("dose    : calculator %.2f U for %.0f g, BG %.1f -> target %.1f, IOB %.2f U%n",
                    ep.bolusUnits, ep.mealCarbs, ep.startGlucose, ep.calculatorTargetGlucose, startIob));
        }
        if (ep.bolusUnits > 0 && ep.preBolusMinutes != 0) {
            postNote(0, ep.bolusUnits, "Bolus", null, null, bolusAt, auth);
            postNote(ep.mealCarbs, 0, ep.mealDescription, null, ep.mealNutrition, t0, auth);
        } else {
            postNote(ep.mealCarbs, ep.bolusUnits, ep.mealDescription, null, ep.mealNutrition, t0, auth);
        }

        // Predict at the first bite (or at the injection if the bolus came after eating).
        LocalDateTime requestAt = bolusAt.isAfter(t0) ? bolusAt : t0;
        List<Map<String, Object>> planned = new ArrayList<>();
        if (ep.secondBolusUnits > 0) {
            // Not injected yet at requestAt -> planned dose; negative minutesAgo = in the future.
            LocalDateTime secondAt = t0.plusMinutes(ep.secondBolusMinutesAfterMeal);
            planned.add(Map.of("carbs", 0.0, "insulin", ep.secondBolusUnits, "meal", "Second bolus",
                    "minutesAgo", (int) -Duration.between(requestAt, secondAt).toMinutes()));
        }
        JsonNode data = calculate(ep.startGlucose, ep.trendMmolPerMin, requestAt, planned, auth);
        JsonNode path = data.path("predictionPath");
        assertTrue(path.isArray() && !path.isEmpty(), "predictionPath is empty - response: " + data);

        report.append(String.format("meal    : %.0f g carbs, bolus %.1f U, pre-bolus %d min, requested at t0%+d min%n",
                ep.mealCarbs, ep.bolusUnits, ep.preBolusMinutes, Duration.between(t0, requestAt).toMinutes()));
        if (ep.secondBolusUnits > 0) {
            report.append(String.format("split   : + %.1f U at t0+%d min (planned at request time)%n",
                    ep.secondBolusUnits, ep.secondBolusMinutesAfterMeal));
        }
        report.append(String.format("after   : IOB %.2f U  COB %.1f g  trend %s  pattern %s  strategy %s%n",
                data.path("activeInsulinOnBoard").asDouble(), data.path("activeCarbsOnBoard").asDouble(),
                data.path("predictionTrend").asText(), data.path("factors").path("matchedPattern").asText("-"),
                data.path("factors").path("bolusStrategy").asText("-")));
        compareAt(failures, report, "+2h", path, t0.plusMinutes(120), ep.actual2h, ep.toleranceMmol);
        compareAt(failures, report, "+4h", path, t0.plusMinutes(240), ep.actual4h, ep.toleranceMmol);
        System.out.println(report);

        assertTrue(failures.isEmpty(), ep.name + ":\n  " + String.join("\n  ", failures) + report);
    }

    /** What the backend actually stored (read back via GET), incl. the ISF in effect at t0. */
    private void appendUserSettings(StringBuilder report, LocalDateTime t0, HttpHeaders auth) throws Exception {
        JsonNode s = get("/api/user-settings", auth);
        JsonNode p = get("/api/user/insulin-preferences", auth);
        MealWindow window = MealWindow.fromTimestamp(t0).orElseThrow();
        JsonNode override = s.path("isf" + window.name().charAt(0) + window.name().substring(1).toLowerCase());
        boolean overridden = override.isNumber();
        report.append(String.format("settings: ISF %s mmol/L/U at %s (%s %s)  default ISF %s%n",
                num(overridden ? override : s.path("isf")), t0.toLocalTime(), window,
                overridden ? "override" : "no override -> default", num(s.path("isf"))));
        report.append(String.format("          ISF windows: breakfast %s  lunch %s  dinner %s  night %s%n",
                num(s.path("isfBreakfast")), num(s.path("isfLunch")), num(s.path("isfDinner")), num(s.path("isfNight"))));
        report.append(String.format("          carbRatio %s mmol/L per 10 g  weight %s kg  carbHalfLife %s min  maxCOB %s min%n",
                num(s.path("carbRatio")), num(s.path("bodyWeightKg")), num(s.path("carbHalfLife")),
                num(s.path("maxCOBDuration"))));
        report.append(String.format("          insulin rapid %s  long-acting %s at %s%n",
                p.path("rapidInsulinCode").asText("-"), p.path("longActingInsulinCode").asText("-"),
                p.path("longActingInjectionTime").asText("-")));
    }

    private static String num(JsonNode n) {
        return n.isNumber() ? String.valueOf(n.asDouble()).replaceAll("\\.0$", "") : "-";
    }

    private void compareAt(List<String> failures, StringBuilder report, String label, JsonNode path,
                           LocalDateTime at, Double actual, double tolerance) {
        JsonNode point = nearestPoint(path, at);
        LocalDateTime pointTime = LocalDateTime.parse(point.path("timestamp").asText());
        if (Math.abs(Duration.between(at, pointTime).toMinutes()) > 5) {
            failures.add(label + ": prediction path does not reach t0" + label + " (last point " + pointTime + ")");
            return;
        }
        double predicted = point.path("predictedGlucose").asDouble();
        if (actual == null) {
            report.append(String.format("%s     : predicted %.1f  (no actual value - not asserted)%n", label, predicted));
            return;
        }
        double err = predicted - actual;
        boolean ok = Math.abs(err) <= tolerance;
        report.append(String.format("%s     : predicted %.1f  actual %.1f  error %+.1f  (±%.1f) %s%n",
                label, predicted, actual, err, tolerance, ok ? "OK" : "FAIL"));
        if (!ok) {
            failures.add(String.format("%s: predicted %.1f vs actual %.1f mmol/L (error %+.1f, tolerance ±%.1f)",
                    label, predicted, actual, err, tolerance));
        }
    }

    private static void checkWithin(List<String> failures, String label, double got, Double expected, double tol) {
        if (expected != null && Math.abs(got - expected) > tol) {
            failures.add(String.format("%s: backend %.2f vs app %.2f (tolerance ±%.2f) - check the history notes",
                    label, got, expected, tol));
        }
    }

    private static JsonNode nearestPoint(JsonNode path, LocalDateTime at) {
        JsonNode best = path.get(0);
        long bestDiff = Long.MAX_VALUE;
        for (JsonNode p : path) {
            long diff = Math.abs(Duration.between(at, LocalDateTime.parse(p.path("timestamp").asText())).toSeconds());
            if (diff < bestDiff) {
                bestDiff = diff;
                best = p;
            }
        }
        return best;
    }

    /** Most recent past occurrence of {@code mealTime} so the whole episode lies before "now". */
    private static LocalDateTime firstBite(String mealTime) {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        LocalDateTime t0 = now.toLocalDate().atTime(LocalTime.parse(mealTime));
        return t0.isAfter(now.minusMinutes(1)) ? t0.minusDays(1) : t0;
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    private HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("episode_" + suffix);
        reg.setEmail("episode+" + suffix + "@example.com");
        reg.setFullName("Episode User");
        reg.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", jsonEntity(reg, new HttpHeaders()), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        ResponseEntity<AuthResponse> resp =
                rest.postForEntity("/api/auth/login", jsonEntity(login, new HttpHeaders()), AuthResponse.class);
        assertNotNull(resp.getBody(), "login failed");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(resp.getBody().getAccessToken());
        return h;
    }

    private void saveSettings(Map<String, Object> settings, HttpHeaders auth) {
        Map<String, Object> body = new LinkedHashMap<>(settings);
        body.putIfAbsent("timezone", "UTC"); // episode times are wall-clock; keep meal windows aligned
        ResponseEntity<String> resp = rest.exchange("/api/user-settings", HttpMethod.POST,
                jsonEntity(body, auth), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "saving settings failed: " + resp.getBody());
    }

    private void saveInsulinPreferences(Map<String, Object> prefs, HttpHeaders auth) {
        ResponseEntity<String> resp = rest.exchange("/api/user/insulin-preferences", HttpMethod.PUT,
                jsonEntity(prefs, auth), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "saving insulin preferences failed: " + resp.getBody());
    }

    private void postNote(double carbs, double insulin, String meal, String type, Nutrition nutrition,
                          LocalDateTime timestamp, HttpHeaders auth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        body.put("carbs", carbs);
        if (insulin > 0) body.put("insulin", insulin);
        body.put("meal", meal);
        if (type != null) body.put("type", type);
        if (nutrition != null) body.put("nutritionProfile", nutritionProfileJson(nutrition, carbs));
        ResponseEntity<String> resp = rest.exchange("/api/notes", HttpMethod.POST,
                jsonEntity(body, auth), String.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "saving note failed: " + body + " -> " + resp.getBody());
    }

    /** The note's nutrition_profile JSON (NutritionSnapshot shape) as the app stores it. */
    private String nutritionProfileJson(Nutrition n, double carbs) {
        Map<String, Object> np = new LinkedHashMap<>();
        np.put("absorptionMode", "GI_GL_ENHANCED");
        np.put("totalCarbs", carbs);
        np.put("estimatedGi", n.gi());
        np.put("glycemicLoad", n.gi() * carbs / 100.0);
        np.put("fiber", n.fiber());
        np.put("protein", n.protein());
        np.put("fat", n.fat());
        if (n.speedClass() != null) np.put("absorptionSpeedClass", n.speedClass());
        try {
            return mapper.writeValueAsString(np);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** POST /api/insulin/calculate exactly as the app does at the moment of dosing. */
    private double recommendedDose(MealEpisode ep, double activeInsulin, LocalDateTime at, HttpHeaders auth)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("carbs", ep.mealCarbs);
        body.put("currentGlucose", ep.startGlucose);
        body.put("targetGlucose", ep.calculatorTargetGlucose);
        body.put("activeInsulin", activeInsulin);
        body.put("clientTimeInfo", clientTimeInfo(at));
        ResponseEntity<String> resp = rest.exchange("/api/insulin/calculate", HttpMethod.POST,
                jsonEntity(body, auth), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "dose calculator refused/failed: " + resp.getBody());
        JsonNode root = mapper.readTree(resp.getBody());
        assertTrue(root.path("backendMode").asBoolean(), "insulin calculator not in backend mode: " + resp.getBody());
        return root.path("data").path("recommendedInsulin").asDouble();
    }

    private static Map<String, Object> clientTimeInfo(LocalDateTime at) {
        return Map.of(
                "timestamp", at.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                "timezone", "UTC",
                "locale", "en-US",
                "timezoneOffset", 0);
    }

    private JsonNode get(String url, HttpHeaders auth) throws Exception {
        ResponseEntity<String> resp = rest.exchange(url, HttpMethod.GET, jsonEntity(null, auth), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "GET " + url + " failed: " + resp.getBody());
        return mapper.readTree(resp.getBody());
    }

    private JsonNode calculate(double currentGlucose, Double trend, LocalDateTime at,
                               List<Map<String, Object>> prospectiveNotes, HttpHeaders auth) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("currentGlucose", currentGlucose);
        body.put("includePredictionFactors", true);
        body.put("clientTimeInfo", clientTimeInfo(at));
        if (trend != null) body.put("currentTrendMmolPerMin", trend);
        if (!prospectiveNotes.isEmpty()) body.put("prospectiveNotes", prospectiveNotes);
        ResponseEntity<String> resp = rest.exchange("/api/glucose-calculations/", HttpMethod.POST,
                jsonEntity(body, auth), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "calculation failed: " + resp.getBody());
        JsonNode root = mapper.readTree(resp.getBody());
        assertTrue(root.path("backendMode").asBoolean(), "backendMode must be true - response: " + resp.getBody());
        return root.path("data");
    }

    private static <T> HttpEntity<T> jsonEntity(T body, HttpHeaders auth) {
        HttpHeaders h = new HttpHeaders();
        h.addAll(auth);
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
