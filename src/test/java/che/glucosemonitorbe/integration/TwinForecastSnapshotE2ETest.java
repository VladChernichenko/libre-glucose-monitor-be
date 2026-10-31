package che.glucosemonitorbe.integration;

import static org.junit.jupiter.api.Assertions.*;

import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshot;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotRepository;
import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.service.UserSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The twin view and the hit rate are served from the forecast frozen when the note was saved. */
class TwinForecastSnapshotE2ETest extends AbstractGlycemicEventE2ETest {

    @Autowired private ForecastSnapshotRepository snapshots;
    @Autowired private UserSettingsService userSettingsService;

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> r = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        return mapper.readTree(r.getBody());
    }

    private ForecastSnapshot awaitSnapshot(UUID noteId) throws Exception {
        for (int i = 0; i < 150; i++) {
            Optional<ForecastSnapshot> s = snapshots.findByNoteId(noteId);
            if (s.isPresent()) return s.get();
            Thread.sleep(100);
        }
        return fail("no snapshot was frozen for the note");
    }

    @Test
    @DisplayName("GET /api/twin/anchors/{noteId} returns the frozen points, even after the user's ISF changes")
    void serveFrozenPointsAfterIsfChange() throws Exception {
        UUID userId = userId();
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setIsf(2.0);
        settings.setCarbRatio(10.0);
        userSettingsService.saveUserSettings(userId, settings);

        long nowMs = nowMs();
        seed(userId, nowMs - 60 * 60_000L, repeat(13, 126)); // 7.0 mmol/L, up to the save moment

        CreateNoteRequest req = new CreateNoteRequest();
        req.setTimestamp(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
        req.setCarbs(0.0);
        req.setInsulin(3.0);
        req.setMeal("correction");
        ResponseEntity<NoteDto> created = rest.exchange("/api/notes", HttpMethod.POST,
                new HttpEntity<>(req, authHeaders), NoteDto.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        UUID noteId = created.getBody().getId();

        ForecastSnapshot snapshot = awaitSnapshot(noteId);
        JsonNode frozen = mapper.readTree(snapshot.getPoints());

        // The CGM moves on and the user's ISF is retuned: the view must not change.
        seed(userId, nowMs + 5 * 60_000L, repeat(20, 90));
        settings.setIsf(6.0);
        userSettingsService.saveUserSettings(userId, settings);

        JsonNode anchors = get("/api/twin/anchors?days=1");
        assertEquals(1, anchors.size());
        assertEquals(noteId.toString(), anchors.get(0).get("noteId").asText());

        JsonNode view = get("/api/twin/anchors/" + noteId);
        JsonNode forecast = view.get("forecast");
        assertEquals(frozen.size(), forecast.size());
        for (int i = 0; i < frozen.size(); i++) {
            assertEquals(frozen.get(i).get("minute").asInt(), forecast.get(i).get("minute").asInt());
            assertEquals(frozen.get(i).get("mmol").asDouble(), forecast.get(i).get("glucose").asDouble(), 1e-9);
            assertEquals(forecast.get(i).get("glucose").asDouble(), forecast.get(i).get("lower").asDouble(), 1e-9);
            assertEquals(forecast.get(i).get("glucose").asDouble(), forecast.get(i).get("upper").asDouble(), 1e-9);
        }
        assertEquals(snapshot.getStartGlucose(), view.get("startGlucose").asDouble(), 1e-9);
        assertEquals(3, view.get("checks").size());
        assertTrue(view.get("pending").asBoolean());
        assertTrue(view.get("actual").size() > 0);
        assertEquals(1, view.get("markers").size());
    }

    @Test
    @DisplayName("A note without a snapshot is not an anchor and has no forecast; excluded snapshots are hidden")
    void noSnapshotNoAnchor() throws Exception {
        UUID userId = userId();
        var older = note(userId, LocalDateTime.now(ZoneOffset.UTC).minusHours(3), 40.0, 4.0, "normal");

        assertEquals(0, get("/api/twin/anchors?days=1").size());
        ResponseEntity<String> r = rest.exchange("/api/twin/anchors/" + older.getId(), HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());

        ForecastSnapshot s = snapshot(userId, older.getId(), Instant.now(), true);
        s.setExcluded(true);
        snapshots.save(s);
        assertEquals(0, get("/api/twin/anchors?days=1").size());
    }

    @Test
    @DisplayName("GET /api/assistant/hit-rate reports the 14-day hit rate")
    void hitRateEndpoint() throws Exception {
        UUID userId = userId();
        var n = note(userId, LocalDateTime.now(ZoneOffset.UTC).minusHours(5), 40.0, 4.0, "normal");
        ForecastSnapshot s = snapshot(userId, n.getId(), Instant.now().minus(5, ChronoUnit.HOURS), false);
        s.setCheck60Hit(true);
        s.setCheck120Hit(false);
        snapshots.save(s);

        JsonNode rate = get("/api/assistant/hit-rate");
        assertEquals(1, rate.get("hits").asInt());
        assertEquals(2, rate.get("checks").asInt());
        assertEquals(50.0, rate.get("percent").asDouble(), 1e-9);
    }

    private ForecastSnapshot snapshot(UUID userId, UUID noteId, Instant madeAt, boolean saveNow) {
        ForecastSnapshot s = new ForecastSnapshot();
        s.setUserId(userId);
        s.setNoteId(noteId);
        s.setMadeAt(madeAt);
        s.setEngine("hovorka");
        s.setStartGlucose(7.0);
        s.setPoints("[{\"minute\":5,\"mmol\":7.0}]");
        return snapshots.save(s);
    }
}
