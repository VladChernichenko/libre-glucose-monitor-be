package che.glucosemonitorbe.sick;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SickProfileServiceTest {

    @Mock SickSegmentRepository segments;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 14, 0);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZoneOffset.UTC);

    private SickProfileService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new SickProfileService(segments, CLOCK);
    }

    private final UUID userId = UUID.randomUUID();

    private SickSegment segment(LocalDateTime from, LocalDateTime endedAt, SickSeverity severity, Double basal) {
        return SickSegment.builder().userId(userId).episodeId(UUID.randomUUID()).severity(severity)
                .startedAt(from).endedAt(endedAt).lastConfirmedAt(from.plusHours(3))
                .healthyBasalUnits(basal).build();
    }

    @Test
    void buildsSpansInOrderAndCarriesTheNewestHealthyBasal() {
        LocalDateTime t0 = NOW.minusDays(5);
        SickSegment older = segment(t0, t0.plusHours(10), SickSeverity.MILD, 20.0);
        SickSegment newest = segment(t0.plusHours(10), null, SickSeverity.MODERATE, 24.0);
        when(segments.findRelevant(eq(userId), any(LocalDateTime.class)))
                .thenReturn(List.of(older, newest));

        SickProfile profile = service.profile(userId, ZONE);

        assertThat(profile.spans()).containsExactly(
                new SickProfile.Span(older.getStartedAt(), older.getEndedAt(), older.getLastConfirmedAt(), SickSeverity.MILD),
                new SickProfile.Span(newest.getStartedAt(), null, newest.getLastConfirmedAt(), SickSeverity.MODERATE));
        assertThat(profile.healthyBasalUnits()).isEqualTo(24.0);
    }

    @Test
    void returnsEmptyForAUserWithNoSegments() {
        when(segments.findRelevant(eq(userId), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(service.profile(userId, ZONE)).isSameAs(SickProfile.EMPTY);
    }

    @Test
    void theCutoffIsFortyDaysAndTheTailBeforeTheUsersWallClock() {
        when(segments.findRelevant(eq(userId), any(LocalDateTime.class))).thenReturn(List.of());

        service.profile(userId, ZONE);

        // UTC clock is 13:00 UTC = 14:00 Berlin; the zone must be applied, not the server clock
        verify(segments).findRelevant(userId, NOW.minusDays(40).minusHours(54));
    }

}
