package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.observer.HypoThresholds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the hypo-prompt lifecycle: opening an event when glucose goes low, expiring it when glucose
 * recovers, and suppressing a re-prompt for a short window after the user resolves one.
 *
 * <p>Detection lives here rather than on the client so that "is this a hypo" has exactly one
 * answer, shared with {@code GlucoseAlertEvaluator} via {@link HypoThresholds}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HypoEventService {

    private final HypoEventRepository repository;
    private final NoteRepository noteRepository;
    private final FeatureToggleConfig featureToggleConfig;

    /**
     * Advance the hypo lifecycle for one user given their latest CGM reading.
     * Called on every observer scan; a no-op when the feature is disabled.
     */
    @Transactional
    public void onGlucoseReading(UUID userId, double glucoseMmol) {
        if (!featureToggleConfig.isHypoRescueLoggingEnabled()) {
            return;
        }

        Optional<HypoEvent> open =
                repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(userId, State.OPEN);

        if (glucoseMmol >= HypoThresholds.RECOVERY_MMOL) {
            open.ifPresent(this::expire);
            return;
        }

        if (glucoseMmol >= HypoThresholds.HYPO_MMOL) {
            // Hysteresis band: not low enough to open, not recovered enough to close.
            return;
        }

        if (open.isPresent()) {
            return;   // already prompting
        }
        if (isSuppressed(userId)) {
            return;
        }

        HypoEvent event = HypoEvent.builder()
                .userId(userId)
                .triggerGlucoseMmol(glucoseMmol)
                .state(State.OPEN)
                .build();
        event.setUpdatedAt(LocalDateTime.now());
        repository.save(event);
        log.info("Hypo event opened for user={} at {} mmol/L", userId, glucoseMmol);
    }

    private void expire(HypoEvent event) {
        event.setState(State.EXPIRED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        repository.save(event);
        log.debug("Hypo event {} expired - glucose recovered", event.getId());
    }

    /**
     * True while the most recent event is still inside its post-resolution quiet window.
     * An EXPIRED event does not suppress: glucose recovered and then fell again, which is a
     * genuinely new hypo.
     */
    private boolean isSuppressed(UUID userId) {
        Optional<HypoEvent> latest = repository.findFirstByUserIdOrderByDetectedAtDesc(userId);
        if (latest.isEmpty()) {
            return false;
        }
        HypoEvent e = latest.get();
        if (e.getState() != State.CONFIRMED && e.getState() != State.DISMISSED) {
            return false;
        }
        LocalDateTime since = e.getResolvedAt() != null ? e.getResolvedAt() : e.getDetectedAt();
        if (since == null) {
            return false;
        }
        return Duration.between(since, LocalDateTime.now()).toMinutes()
                < HypoThresholds.SUPPRESSION_MINUTES;
    }

    /** Largest single rescue dose accepted [g]. Above this is a typo, not a treatment. */
    private static final double MAX_RESCUE_GRAMS = 100.0;

    @Transactional(readOnly = true)
    public List<HypoEventDTO> list(UUID userId, State stateFilter) {
        List<HypoEvent> events = stateFilter == null
                ? repository.findByUserIdOrderByDetectedAtDesc(userId)
                : repository.findByUserIdAndStateOrderByDetectedAtDesc(userId, stateFilter);
        return events.stream().map(HypoEventDTO::from).toList();
    }

    /**
     * Log a rescue carb against an open hypo event.
     *
     * <p>Idempotent by design: confirming an already-confirmed event returns the note that was
     * already created rather than logging a second one. Double-logged rescue carbs would suppress
     * the next genuine prompt and inflate the prediction while the user is still low.
     */
    @Transactional
    public HypoEventDTO confirm(UUID userId, UUID eventId, Double grams) {
        HypoEvent event = requireOwnEvent(userId, eventId);

        if (event.getState() == State.CONFIRMED) {
            return HypoEventDTO.from(event);
        }
        if (event.getState() != State.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "409 Hypo event is no longer open");
        }

        double amount = validateGrams(grams);

        Note note = new Note();
        note.setUserId(userId);
        note.setTimestamp(LocalDateTime.now());
        note.setCarbs(amount);
        note.setInsulin(0.0);
        note.setMeal("Hypo treatment");
        note.setType(Note.TYPE_HYPO_TREATMENT);
        note.setCreatedAt(LocalDateTime.now());
        note.setUpdatedAt(LocalDateTime.now());
        Note saved = noteRepository.save(note);

        event.setState(State.CONFIRMED);
        event.setNoteId(saved.getId());
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        log.info("Hypo event {} confirmed with {} g rescue carbs", eventId, amount);
        return HypoEventDTO.from(repository.save(event));
    }

    @Transactional
    public HypoEventDTO dismiss(UUID userId, UUID eventId) {
        HypoEvent event = requireOwnEvent(userId, eventId);
        if (event.getState() == State.DISMISSED) {
            return HypoEventDTO.from(event);
        }
        if (event.getState() != State.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "409 Hypo event is no longer open");
        }
        event.setState(State.DISMISSED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        return HypoEventDTO.from(repository.save(event));
    }

    /** 404 rather than 403 for another user's event - do not disclose that it exists. */
    private HypoEvent requireOwnEvent(UUID userId, UUID eventId) {
        HypoEvent event = repository.findById(eventId)
                .filter(e -> e.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "404 Hypo event not found"));
        return event;
    }

    private double validateGrams(Double grams) {
        if (grams == null || !Double.isFinite(grams) || grams <= 0 || grams > MAX_RESCUE_GRAMS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "400 grams must be greater than 0 and at most " + MAX_RESCUE_GRAMS);
        }
        return grams;
    }
}
