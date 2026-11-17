package che.glucosemonitorbe.sick;

import che.glucosemonitorbe.domain.UserZones;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Turns a user's stored illness segments into a {@link SickProfile}. */
@Service
public class SickProfileService {

    /** Covers the 30-day calibration window plus tails. */
    static final int HISTORY_DAYS = 40;

    /** A segment's influence outlasts its end by the lapse window plus the learning tail. */
    static final int TAIL_HOURS = SickProfile.LAPSE_HOURS + SickProfile.LEARNING_TAIL_HOURS;

    private final SickSegmentRepository segments;
    private final Clock clock;

    @Autowired
    public SickProfileService(SickSegmentRepository segments) {
        this(segments, Clock.systemUTC());
    }

    SickProfileService(SickSegmentRepository segments, Clock clock) {
        this.segments = segments;
        this.clock = clock;
    }

    /**
     * The user's profile: every segment still open or ended within the history window, however long
     * ago it started. {@code zone} is the user's zone (segments are stored in user wall time); it is
     * passed in so this service need not read settings, which would be circular.
     */
    @Transactional(readOnly = true)
    public SickProfile profile(UUID userId, ZoneId zone) {
        LocalDateTime wallNow = UserZones.toWallTime(clock.millis(), zone);
        LocalDateTime cutoff = wallNow.minusDays(HISTORY_DAYS).minusHours(TAIL_HOURS);
        List<SickSegment> recent = segments.findRelevant(userId, cutoff);
        if (recent.isEmpty()) {
            return SickProfile.EMPTY;
        }
        List<SickProfile.Span> spans = recent.stream()
                .map(s -> new SickProfile.Span(s.getStartedAt(), s.getEndedAt(), s.getLastConfirmedAt(), s.getSeverity()))
                .toList();
        return new SickProfile(spans, recent.get(recent.size() - 1).getHealthyBasalUnits());
    }
}
