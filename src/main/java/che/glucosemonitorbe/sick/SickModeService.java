package che.glucosemonitorbe.sick;

import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.UserSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Starts, changes, confirms and ends a user's illness episode and reports its status. An open
 * segment that has gone {@link SickProfile#LAPSE_HOURS} hours without confirmation is closed first
 * by every method, so callers never see a lapsed segment as active.
 */
@Service
public class SickModeService {

    static final String REASON_USER = "USER";
    static final String REASON_MORNING_NO = "MORNING_NO";
    static final String REASON_LAPSED = "LAPSED";
    static final String REASON_SEVERITY_CHANGE = "SEVERITY_CHANGE";

    private static final LocalTime MORNING = LocalTime.of(6, 0);

    private final SickSegmentRepository segments;
    private final NoteRepository notes;
    private final UserSettingsService userSettingsService;
    private final BasalInsulinResolver basalResolver;
    private final Clock clock;

    @Autowired
    public SickModeService(SickSegmentRepository segments, NoteRepository notes,
                           UserSettingsService userSettingsService, BasalInsulinResolver basalResolver) {
        this(segments, notes, userSettingsService, basalResolver, Clock.systemUTC());
    }

    SickModeService(SickSegmentRepository segments, NoteRepository notes,
                    UserSettingsService userSettingsService, BasalInsulinResolver basalResolver, Clock clock) {
        this.segments = segments;
        this.notes = notes;
        this.userSettingsService = userSettingsService;
        this.basalResolver = basalResolver;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public SickModeStatusDTO status(UUID userId) {
        LocalDateTime now = now(userId);
        closeLapsed(userId, now);
        return statusOf(userId, now);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public SickModeStatusDTO start(UUID userId, SickSeverity severity, Since since) {
        requireSelectable(severity);
        LocalDateTime now = now(userId);
        closeLapsed(userId, now);
        if (segments.findByUserIdAndEndedAtIsNull(userId).isPresent()) {
            throw new IllegalArgumentException("Sick mode is already active");
        }
        LocalDateTime startedAt = startOf(since, now);
        segments.findFirstByUserIdOrderByStartedAtDesc(userId).ifPresent(previous -> {
            if (startedAt.isBefore(effectiveEnd(previous))) {
                throw new IllegalArgumentException("Sick mode cannot start before the previous episode ended");
            }
        });
        segments.save(SickSegment.builder()
                .userId(userId)
                .episodeId(UUID.randomUUID())
                .severity(severity)
                .startedAt(startedAt)
                .lastConfirmedAt(now)
                .healthyBasalUnits(healthyBasalBefore(userId, startedAt))
                .build());
        return finish(userId, now);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public SickModeStatusDTO changeSeverity(UUID userId, SickSeverity severity) {
        requireSelectable(severity);
        return changeSeverityInternal(userId, severity);
    }

    /** Like {@link #changeSeverity} but without the {@code selectable()} check; for accepted step-ups. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    SickModeStatusDTO changeSeverityInternal(UUID userId, SickSeverity severity) {
        if (severity == null) {
            throw new IllegalArgumentException("severity is required");
        }
        LocalDateTime now = now(userId);
        closeLapsed(userId, now);
        SickSegment open = requireOpen(userId);
        open.setEndedAt(now);
        open.setEndReason(REASON_SEVERITY_CHANGE);
        segments.save(open);
        segments.save(SickSegment.builder()
                .userId(userId)
                .episodeId(open.getEpisodeId())
                .severity(severity)
                .startedAt(now)
                .lastConfirmedAt(now)
                .healthyBasalUnits(open.getHealthyBasalUnits())
                .build());
        return finish(userId, now);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public SickModeStatusDTO confirm(UUID userId) {
        LocalDateTime now = now(userId);
        closeLapsed(userId, now);
        SickSegment open = requireOpen(userId);
        open.setLastConfirmedAt(now);
        segments.save(open);
        return finish(userId, now);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public SickModeStatusDTO end(UUID userId, String reason) {
        if (!REASON_USER.equals(reason) && !REASON_MORNING_NO.equals(reason)) {
            throw new IllegalArgumentException("reason must be USER or MORNING_NO");
        }
        LocalDateTime now = now(userId);
        closeLapsed(userId, now);
        segments.findByUserIdAndEndedAtIsNull(userId).ifPresent(open -> {
            open.setEndedAt(now);
            open.setEndReason(reason);
            segments.save(open);
        });
        return finish(userId, now);
    }

    // -- internals -------------------------------------------------------------

    private SickModeStatusDTO finish(UUID userId, LocalDateTime now) {
        evictAfterCommit(userId);
        return statusOf(userId, now);
    }

    /**
     * Drops the cached settings once the change is committed, so a concurrent reader cannot
     * re-cache the old state in between; evicts at once when no transaction is active.
     */
    private void evictAfterCommit(UUID userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    userSettingsService.evict(userId);
                }
            });
        } else {
            userSettingsService.evict(userId);
        }
    }

    private SickModeStatusDTO statusOf(UUID userId, LocalDateTime now) {
        return segments.findByUserIdAndEndedAtIsNull(userId).map(open -> {
            LocalDateTime since = segments.findFirstByUserIdAndEpisodeIdOrderByStartedAtAsc(userId, open.getEpisodeId())
                    .map(SickSegment::getStartedAt).orElse(open.getStartedAt());
            SickSeverity severity = open.getSeverity();
            return new SickModeStatusDTO(true, severity, since,
                    (int) Duration.between(since, now).toDays() + 1,
                    (int) Math.round((severity.isfMultiplier() - 1) * 100),
                    (int) Math.round(severity.basalNeed() * 100),
                    open.getLastConfirmedAt().toLocalDate().isBefore(now.toLocalDate()),
                    null);
        }).orElseGet(SickModeStatusDTO::inactive);
    }

    /** Closes an open segment that has gone 48 h without confirmation, ending it at the lapse instant. */
    private void closeLapsed(UUID userId, LocalDateTime now) {
        segments.findByUserIdAndEndedAtIsNull(userId).ifPresent(open -> {
            LocalDateTime lapseAt = open.getLastConfirmedAt().plusHours(SickProfile.LAPSE_HOURS);
            if (!lapseAt.isAfter(now)) {
                open.setEndedAt(lapseAt);
                open.setEndReason(REASON_LAPSED);
                segments.save(open);
                evictAfterCommit(userId);
            }
        });
    }

    private SickSegment requireOpen(UUID userId) {
        return segments.findByUserIdAndEndedAtIsNull(userId)
                .orElseThrow(() -> new IllegalArgumentException("Sick mode is not active"));
    }

    private static void requireSelectable(SickSeverity severity) {
        if (severity == null || !severity.selectable()) {
            throw new IllegalArgumentException("Severity is not selectable: " + severity);
        }
    }

    private static LocalDateTime effectiveEnd(SickSegment s) {
        LocalDateTime lapse = s.getLastConfirmedAt().plusHours(SickProfile.LAPSE_HOURS);
        return s.getEndedAt() != null && s.getEndedAt().isBefore(lapse) ? s.getEndedAt() : lapse;
    }

    private static LocalDateTime startOf(Since since, LocalDateTime now) {
        LocalDateTime start = switch (since == null ? Since.NOW : since) {
            case NOW -> now;
            case THIS_MORNING -> now.toLocalDate().atTime(MORNING);
            case YESTERDAY -> now.toLocalDate().minusDays(1).atTime(MORNING);
        };
        return start.isAfter(now) ? now : start;
    }

    /** The usual daily long-acting dose just before {@code startedAt}; null when none is on record. */
    private Double healthyBasalBefore(UUID userId, LocalDateTime startedAt) {
        List<Note> longActing = notes.findByUserIdAndTimestampBetween(
                        userId, startedAt.minusHours(BasalInsulinResolver.LOOKBACK_HOURS), startedAt).stream()
                .filter(Note::isLongActing)
                .filter(n -> n.getTimestamp().isBefore(startedAt))
                .toList();
        double usual = basalResolver.usualDailyUnits(longActing, startedAt);
        return usual > 0 ? usual : null;
    }

    private LocalDateTime now(UUID userId) {
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        return UserZones.toWallTime(clock.millis(),
                UserZones.resolve(settings.getTimezone(), settings.getUtcOffsetMinutes()));
    }
}
