package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.service.observer.HypoThresholds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
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
}
