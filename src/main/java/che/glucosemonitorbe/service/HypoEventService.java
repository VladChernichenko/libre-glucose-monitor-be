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
            open.ifPresent(e -> expire(e, "glucose recovered"));
            markRecovered(userId);
            return;
        }

        if (glucoseMmol >= HypoThresholds.HYPO_MMOL) {
            // Hysteresis band: not low enough to open, not recovered enough to close.
            return;
        }

        // An OPEN event that has aged past its useful life is not a live prompt any more, and
        // holding it open would also block a genuinely current one from opening below.
        if (open.isPresent() && !expireIfStale(open.get())) {
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

    private void expire(HypoEvent event, String reason) {
        event.setState(State.EXPIRED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        repository.save(event);
        log.debug("Hypo event {} expired - {}", event.getId(), reason);
    }

    /**
     * Expire {@code event} when it has been OPEN longer than
     * {@link HypoThresholds#MAX_OPEN_MINUTES}, and report whether it did.
     *
     * <p>An OPEN event is otherwise closed only by a recovery reading, and the detector needs two
     * CGM readings inside a 20-minute window to produce one at all. A sensor change, an offline
     * phone or the feature flag being switched off therefore strands the row OPEN indefinitely, and
     * the client faithfully shows that stale prompt on next launch - possibly the next morning -
     * with a trigger glucose from hours ago. Confirming it then writes a {@code hypo_treatment}
     * note at <em>now</em>, injecting phantom fast carbs into COB, Hovorka and the twin fit.
     *
     * <p>A null {@code detectedAt} cannot be aged and is left alone; the column is
     * {@code NOT NULL} in the database, so that only arises for an unsaved instance.
     *
     * @return true when the event was expired by this call
     */
    private boolean expireIfStale(HypoEvent event) {
        if (event.getState() != State.OPEN || event.getDetectedAt() == null) {
            return false;
        }
        if (Duration.between(event.getDetectedAt(), LocalDateTime.now()).toMinutes()
                < HypoThresholds.MAX_OPEN_MINUTES) {
            return false;
        }
        expire(event, "open longer than " + HypoThresholds.MAX_OPEN_MINUTES + " min");
        return true;
    }

    /**
     * Record that the user's glucose came back up after their most recent event was resolved.
     *
     * <p>This is what ends the suppression window early - see {@link #isSuppressed}. Written once
     * per event: the guard on {@code recoveredAt == null} keeps the 5-minute scan from issuing a
     * write on every in-range reading for the rest of the user's life.
     */
    private void markRecovered(UUID userId) {
        repository.findFirstByUserIdOrderByDetectedAtDesc(userId).ifPresent(e -> {
            boolean resolvedByUser = e.getState() == State.CONFIRMED || e.getState() == State.DISMISSED;
            if (!resolvedByUser || e.getRecoveredAt() != null) {
                return;
            }
            e.setRecoveredAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            repository.save(e);
        });
    }

    /**
     * True while the most recent event is still inside its post-resolution quiet window.
     *
     * <p>An EXPIRED event does not suppress: glucose recovered and then fell again, which is a
     * genuinely new hypo.
     *
     * <p>Neither does a resolved event the user has since <em>recovered</em> from. Keying only on
     * elapsed time made the window block a real relapse - dismiss at 3.8, recover to 4.6, crash to
     * 3.2 twelve minutes later, no prompt - which contradicts the requirement that a new hypo after
     * recovery opens a fresh prompt. Suppression exists to stop the prompt looping every 5 minutes
     * <em>within one episode</em>; a recovery reading ends the episode, so it ends the window with
     * it. Both purposes are served: while the user is still low, nothing sets {@code recoveredAt}
     * and the 15-minute quiet period stands.
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
        if (e.getRecoveredAt() != null) {
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

    /**
     * List the user's events, aging out any stale OPEN row first.
     *
     * <p>Deliberately not {@code readOnly}: the age-out has to happen here as well as on the CGM
     * scan, because the scan is exactly what stops running in the cases that strand a row OPEN (a
     * sensor change or an offline phone starves {@code GlucoseAnomalyDetector} of the two readings
     * it needs). Sweeping on read is what guarantees the client can never be handed a prompt that
     * is no longer live.
     */
    @Transactional
    public List<HypoEventDTO> list(UUID userId, State stateFilter) {
        List<HypoEvent> events = stateFilter == null
                ? repository.findByUserIdOrderByDetectedAtDesc(userId)
                : repository.findByUserIdAndStateOrderByDetectedAtDesc(userId, stateFilter);
        events.forEach(this::expireIfStale);
        return events.stream()
                .filter(e -> stateFilter == null || e.getState() == stateFilter)
                .map(HypoEventDTO::from)
                .toList();
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
        // A prompt the client held on to past its useful life must not be able to write a note at
        // now() for a hypo that ended hours ago - the phantom-carb path this age-out exists to
        // close. Checked here as well as on read: the client may be acting on a cached event.
        expireIfStale(event);
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
        // Same age-out as confirm, for a different reason: DISMISSED opens a 15-minute suppression
        // window, and a stale prompt must not be able to buy silence for a hypo happening now.
        expireIfStale(event);
        if (event.getState() != State.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "409 Hypo event is no longer open");
        }
        event.setState(State.DISMISSED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        return HypoEventDTO.from(repository.save(event));
    }

    /**
     * 404 rather than 403 for another user's event - do not disclose that it exists.
     *
     * <p>Uses {@link HypoEventRepository#findByIdForUpdate} rather than plain {@code findById}:
     * this method backs both {@code confirm} and {@code dismiss}, and both are reached over HTTP
     * from a phone during a hypo, where a double-tap or client retry is expected, not exceptional.
     * The pessimistic write lock (held for the rest of the caller's already-{@code @Transactional}
     * method) makes a second concurrent caller block until the first commits, then re-read the now
     * -resolved state and take the idempotent early-return path instead of writing a second note.
     */
    private HypoEvent requireOwnEvent(UUID userId, UUID eventId) {
        HypoEvent event = repository.findByIdForUpdate(eventId)
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
