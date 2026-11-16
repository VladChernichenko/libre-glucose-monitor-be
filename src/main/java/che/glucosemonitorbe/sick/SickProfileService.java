package che.glucosemonitorbe.sick;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Turns a user's stored illness segments into a {@link SickProfile}. */
@Service
@RequiredArgsConstructor
public class SickProfileService {

    /** Covers the 30-day calibration window plus tails. */
    static final int HISTORY_DAYS = 40;

    private final SickSegmentRepository segments;

    @Transactional(readOnly = true)
    public SickProfile profile(UUID userId) {
        List<SickSegment> recent = segments.findByUserIdAndStartedAtAfterOrderByStartedAtAsc(
                userId, LocalDateTime.now().minusDays(HISTORY_DAYS));
        if (recent.isEmpty()) {
            return SickProfile.EMPTY;
        }
        List<SickProfile.Span> spans = recent.stream()
                .map(s -> new SickProfile.Span(s.getStartedAt(), s.getEndedAt(), s.getLastConfirmedAt(), s.getSeverity()))
                .toList();
        return new SickProfile(spans, recent.get(recent.size() - 1).getHealthyBasalUnits());
    }
}
