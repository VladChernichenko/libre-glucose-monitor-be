package che.glucosemonitorbe.assistant.snapshot;

import che.glucosemonitorbe.assistant.snapshot.SnapshotForecaster.FrozenForecast;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.UserSettingsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Freezes the dashboard forecast when an anchor note is saved (spec §6.5). All times passed in are
 * the user's wall time, the clock {@code notes.timestamp} lives on.
 *
 * <ul>
 *   <li>Anchor = rapid dose &gt; 0, carbs &ge; 10 g, or fast sugar (hypo treatment).</li>
 *   <li>A note timed more than 10 min before its save is backdated: no snapshot.</li>
 *   <li>Dose/carbs/time edits within 15 min of the original save re-freeze; later ones mark the
 *       snapshot excluded.</li>
 *   <li>A pre-bolus dose whose meal is then eaten is excluded ({@link #onNoteExcluded}).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastSnapshotService {

    static final double MIN_ANCHOR_CARBS = 10.0;
    static final Duration BACKDATE_LIMIT = Duration.ofMinutes(10);
    static final Duration REFREEZE_WINDOW = Duration.ofMinutes(15);

    private final ForecastSnapshotRepository repository;
    private final NoteRepository noteRepository;
    private final SnapshotForecaster forecaster;
    private final UserSettingsService userSettingsService;
    private final ObjectMapper objectMapper;

    public void onNoteSaved(UUID userId, UUID noteId, LocalDateTime savedAt) {
        Optional<Note> note = noteRepository.findByIdAndUserId(noteId, userId);
        if (note.isEmpty() || !isAnchor(note.get()) || isBackdated(note.get(), savedAt)) return;
        freeze(userId, note.get(), savedAt, repository.findByNoteId(noteId).orElseGet(ForecastSnapshot::new));
    }

    /**
     * A note's dose, carbs, time or type changed.
     *
     * @param originalSavedAt the note's first save, user wall time
     * @param now             the edit moment, user wall time
     */
    public void onNoteUpdated(UUID userId, UUID noteId, LocalDateTime originalSavedAt, LocalDateTime now) {
        Optional<ForecastSnapshot> existing = repository.findByNoteId(noteId);
        boolean inWindow = !now.isAfter(originalSavedAt.plus(REFREEZE_WINDOW));
        if (!inWindow) {
            existing.filter(s -> !s.isExcluded()).ifPresent(s -> {
                s.setExcluded(true);
                repository.save(s);
            });
            return;
        }
        Optional<Note> note = noteRepository.findByIdAndUserId(noteId, userId);
        // Backdating is judged against the original save: an in-window edit of a note logged "now"
        // must not turn it into a backdated one just because a few minutes have passed.
        if (note.isEmpty() || !isAnchor(note.get()) || isBackdated(note.get(), originalSavedAt)) {
            if (existing.isPresent()) repository.deleteByNoteId(noteId);
            return;
        }
        freeze(userId, note.get(), now, existing.orElseGet(ForecastSnapshot::new));
    }

    /**
     * Marks the note's snapshot excluded from scoring, e.g. a pre-bolus dose whose meal was eaten:
     * its frozen forecast had the dose alone, so it would score as a miss. A later in-window re-freeze
     * keeps it excluded.
     */
    public void onNoteExcluded(UUID noteId) {
        repository.findByNoteId(noteId).filter(s -> !s.isExcluded()).ifPresent(s -> {
            s.setExcluded(true);
            repository.save(s);
        });
    }

    public void onNoteDeleted(UUID noteId) {
        repository.deleteByNoteId(noteId);
    }

    /** An absolute instant on this user's wall clock. */
    public LocalDateTime wallTime(UUID userId, Instant at) {
        return LocalDateTime.ofInstant(at, zone(userId));
    }

    static boolean isAnchor(Note note) {
        double insulin = note.getInsulin() != null ? note.getInsulin() : 0.0;
        double carbs = note.getCarbs() != null ? note.getCarbs() : 0.0;
        return (insulin > 0 && !note.isLongActing())
                || carbs >= MIN_ANCHOR_CARBS
                || note.isHypoTreatment();
    }

    static boolean isBackdated(Note note, LocalDateTime savedAt) {
        return note.getTimestamp() != null && note.getTimestamp().isBefore(savedAt.minus(BACKDATE_LIMIT));
    }

    private void freeze(UUID userId, Note note, LocalDateTime savedAt, ForecastSnapshot snapshot) {
        Optional<FrozenForecast> forecast = forecaster.forecast(userId, savedAt, note.getGlucoseLevel());
        if (forecast.isEmpty()) {
            log.info("No forecast snapshot for note {}: no starting glucose or empty forecast", note.getId());
            return;
        }
        FrozenForecast f = forecast.get();
        snapshot.setUserId(userId);
        snapshot.setNoteId(note.getId());
        snapshot.setMadeAt(savedAt.atZone(zone(userId)).toInstant());
        snapshot.setEngine(f.engine());
        snapshot.setModelVersion(f.modelVersion());
        snapshot.setStartGlucose(f.startGlucose());
        snapshot.setPoints(toJson(f));
        // A new snapshot starts included; an excluded one stays excluded (an out-of-window edit or a
        // pre-bolus whose meal was eaten), since an in-window edit cannot make its forecast scoreable.
        snapshot.setComplete(false);
        snapshot.setCheck60Actual(null);
        snapshot.setCheck60Hit(null);
        snapshot.setCheck120Actual(null);
        snapshot.setCheck120Hit(null);
        snapshot.setCheck240Actual(null);
        snapshot.setCheck240Hit(null);
        repository.save(snapshot);
    }

    private String toJson(FrozenForecast f) {
        try {
            return objectMapper.writeValueAsString(f.points());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise forecast points", e);
        }
    }

    private ZoneId zone(UUID userId) {
        UserSettingsDTO s = userSettingsService.getUserSettings(userId);
        return s == null ? ZoneOffset.UTC : UserZones.resolve(s.getTimezone(), s.getUtcOffsetMinutes());
    }
}
