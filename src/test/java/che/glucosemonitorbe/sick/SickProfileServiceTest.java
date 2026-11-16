package che.glucosemonitorbe.sick;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SickProfileServiceTest {

    @Mock SickSegmentRepository segments;
    @InjectMocks SickProfileService service;

    private final UUID userId = UUID.randomUUID();

    private SickSegment segment(LocalDateTime from, LocalDateTime endedAt, SickSeverity severity, Double basal) {
        return SickSegment.builder().userId(userId).episodeId(UUID.randomUUID()).severity(severity)
                .startedAt(from).endedAt(endedAt).lastConfirmedAt(from.plusHours(3))
                .healthyBasalUnits(basal).build();
    }

    @Test
    void buildsSpansInOrderAndCarriesTheNewestHealthyBasal() {
        LocalDateTime t0 = LocalDateTime.now().minusDays(5);
        SickSegment older = segment(t0, t0.plusHours(10), SickSeverity.MILD, 20.0);
        SickSegment newest = segment(t0.plusHours(10), null, SickSeverity.MODERATE, 24.0);
        when(segments.findByUserIdAndStartedAtAfterOrderByStartedAtAsc(eq(userId), any(LocalDateTime.class)))
                .thenReturn(List.of(older, newest));

        SickProfile profile = service.profile(userId);

        assertThat(profile.spans()).containsExactly(
                new SickProfile.Span(older.getStartedAt(), older.getEndedAt(), older.getLastConfirmedAt(), SickSeverity.MILD),
                new SickProfile.Span(newest.getStartedAt(), null, newest.getLastConfirmedAt(), SickSeverity.MODERATE));
        assertThat(profile.healthyBasalUnits()).isEqualTo(24.0);
    }

    @Test
    void returnsEmptyForAUserWithNoSegments() {
        when(segments.findByUserIdAndStartedAtAfterOrderByStartedAtAsc(eq(userId), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(service.profile(userId)).isSameAs(SickProfile.EMPTY);
    }
}
