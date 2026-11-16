package che.glucosemonitorbe.sick;

import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SickModeServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 10, 0);

    @Mock SickSegmentRepository segments;
    @Mock NoteRepository notes;
    @Mock UserSettingsService userSettingsService;
    @Mock BasalInsulinResolver basalResolver;

    private final UUID userId = UUID.randomUUID();
    private SickModeService service;

    @BeforeEach
    void setUp() {
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setTimezone("UTC");
        lenient().when(userSettingsService.getUserSettings(userId)).thenReturn(settings);
        lenient().when(segments.save(any(SickSegment.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(segments.findByUserIdAndEndedAtIsNull(userId)).thenReturn(Optional.empty());
        lenient().when(segments.findFirstByUserIdOrderByStartedAtDesc(userId)).thenReturn(Optional.empty());
        lenient().when(notes.findByUserIdAndTimestampBetween(any(), any(), any())).thenReturn(List.of());
        lenient().when(basalResolver.usualDailyUnits(any(), any())).thenReturn(0.0);
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new SickModeService(segments, notes, userSettingsService, basalResolver, clock);
    }

    private SickSegment open(SickSeverity severity, LocalDateTime startedAt, LocalDateTime confirmedAt) {
        SickSegment s = SickSegment.builder().userId(userId).episodeId(UUID.randomUUID()).severity(severity)
                .startedAt(startedAt).lastConfirmedAt(confirmedAt).healthyBasalUnits(24.0).build();
        when(segments.findByUserIdAndEndedAtIsNull(userId)).thenReturn(Optional.of(s));
        lenient().when(segments.findFirstByUserIdAndEpisodeIdOrderByStartedAtAsc(userId, s.getEpisodeId()))
                .thenReturn(Optional.of(s));
        return s;
    }

    private SickSegment saved(int index) {
        ArgumentCaptor<SickSegment> captor = ArgumentCaptor.forClass(SickSegment.class);
        verify(segments, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(index);
    }

    @Test
    void startOpensASegmentAndFreezesTheHealthyBasal() {
        Note basal = new Note();
        basal.setType(Note.TYPE_LONG_ACTING);
        basal.setTimestamp(NOW.minusHours(10));
        basal.setInsulin(24.0);
        when(notes.findByUserIdAndTimestampBetween(userId, NOW.minusHours(BasalInsulinResolver.LOOKBACK_HOURS), NOW))
                .thenReturn(List.of(basal));
        when(basalResolver.usualDailyUnits(List.of(basal), NOW)).thenReturn(24.0);

        SickModeStatusDTO status = service.start(userId, SickSeverity.MILD, Since.NOW);

        SickSegment s = saved(0);
        assertThat(s.getSeverity()).isEqualTo(SickSeverity.MILD);
        assertThat(s.getStartedAt()).isEqualTo(NOW);
        assertThat(s.getLastConfirmedAt()).isEqualTo(NOW);
        assertThat(s.getEpisodeId()).isNotNull();
        assertThat(s.getHealthyBasalUnits()).isEqualTo(24.0);
        assertThat(status.active()).isFalse(); // mocked repository still reports no open segment
    }

    @Test
    void startStoresNoHealthyBasalWhenThereIsNone() {
        service.start(userId, SickSeverity.MILD, Since.NOW);
        assertThat(saved(0).getHealthyBasalUnits()).isNull();
    }

    @Test
    void startBackdatedToThisMorningBeginsAtSixToday() {
        service.start(userId, SickSeverity.MODERATE, Since.THIS_MORNING);
        assertThat(saved(0).getStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 16, 6, 0));
    }

    @Test
    void startBackdatedToYesterdayBeginsAtSixTheDayBefore() {
        service.start(userId, SickSeverity.MODERATE, Since.YESTERDAY);
        assertThat(saved(0).getStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 6, 0));
    }

    @Test
    void aBackdatedStartLaterThanNowIsClampedToNow() {
        Clock early = Clock.fixed(LocalDateTime.of(2026, 11, 16, 3, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        SickModeService s = new SickModeService(segments, notes, userSettingsService, basalResolver, early);
        s.start(userId, SickSeverity.MILD, Since.THIS_MORNING);
        assertThat(saved(0).getStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 16, 3, 0));
    }

    @Test
    void startCannotReachBeforeThePreviousEpisodesEnd() {
        SickSegment previous = SickSegment.builder().userId(userId).episodeId(UUID.randomUUID())
                .severity(SickSeverity.MILD).startedAt(NOW.minusDays(2))
                .endedAt(LocalDateTime.of(2026, 11, 16, 8, 0)).endReason("USER")
                .lastConfirmedAt(NOW.minusHours(3)).build();
        when(segments.findFirstByUserIdOrderByStartedAtDesc(userId)).thenReturn(Optional.of(previous));

        assertThatThrownBy(() -> service.start(userId, SickSeverity.MILD, Since.THIS_MORNING))
                .isInstanceOf(IllegalArgumentException.class);
        verify(segments, never()).save(any());
        assertThat(service.start(userId, SickSeverity.MILD, Since.NOW).active()).isFalse();
    }

    @Test
    void startRejectsTheUnselectableLevel() {
        assertThatThrownBy(() -> service.start(userId, SickSeverity.SEVERE_UNRESPONSIVE, Since.NOW))
                .isInstanceOf(IllegalArgumentException.class);
        verify(segments, never()).save(any());
    }

    @Test
    void startRejectsWhileAnActiveSegmentIsOpen() {
        open(SickSeverity.MILD, NOW.minusHours(5), NOW.minusHours(5));
        assertThatThrownBy(() -> service.start(userId, SickSeverity.MILD, Since.NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeSeverityClosesAndReopensWithinTheSameEpisode() {
        SickSegment before = open(SickSeverity.MILD, NOW.minusHours(5), NOW.minusHours(5));

        service.changeSeverity(userId, SickSeverity.SEVERE);

        assertThat(saved(0)).isSameAs(before);
        assertThat(before.getEndedAt()).isEqualTo(NOW);
        assertThat(before.getEndReason()).isEqualTo("SEVERITY_CHANGE");
        SickSegment next = saved(1);
        assertThat(next.getSeverity()).isEqualTo(SickSeverity.SEVERE);
        assertThat(next.getEpisodeId()).isEqualTo(before.getEpisodeId());
        assertThat(next.getStartedAt()).isEqualTo(NOW);
        assertThat(next.getLastConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    void changeSeverityCarriesTheFrozenHealthyBasalForward() {
        SickSegment before = open(SickSeverity.MILD, NOW.minusHours(5), NOW.minusHours(5));

        service.changeSeverity(userId, SickSeverity.MODERATE);

        assertThat(saved(1).getHealthyBasalUnits()).isEqualTo(before.getHealthyBasalUnits()).isEqualTo(24.0);
        assertThat(saved(1).getEpisodeId()).isEqualTo(before.getEpisodeId());
    }

    @Test
    void changeSeverityRejectsTheUnselectableLevelButTheInternalOverloadAllowsIt() {
        open(SickSeverity.SEVERE, NOW.minusHours(5), NOW.minusHours(5));
        assertThatThrownBy(() -> service.changeSeverity(userId, SickSeverity.SEVERE_UNRESPONSIVE))
                .isInstanceOf(IllegalArgumentException.class);
        service.changeSeverityInternal(userId, SickSeverity.SEVERE_UNRESPONSIVE);
        assertThat(saved(1).getSeverity()).isEqualTo(SickSeverity.SEVERE_UNRESPONSIVE);
    }

    @Test
    void anUnconfirmedSegmentLapsesAt48hAndANewStartThenSucceeds() {
        LocalDateTime confirmed = NOW.minusHours(60);
        SickSegment stale = open(SickSeverity.MODERATE, NOW.minusHours(70), confirmed);
        when(segments.findFirstByUserIdOrderByStartedAtDesc(userId)).thenReturn(Optional.of(stale));
        // first read (lapse check) sees the stale segment; once closed it is no longer open
        when(segments.findByUserIdAndEndedAtIsNull(userId)).thenReturn(Optional.of(stale), Optional.empty());

        service.start(userId, SickSeverity.MILD, Since.NOW);

        assertThat(stale.getEndedAt()).isEqualTo(confirmed.plusHours(48));
        assertThat(stale.getEndReason()).isEqualTo("LAPSED");
        assertThat(saved(1).getSeverity()).isEqualTo(SickSeverity.MILD);
        assertThat(saved(1).getEpisodeId()).isNotEqualTo(stale.getEpisodeId());
    }

    @Test
    void aSegmentJustShortOfTheLapseStaysOpen() {
        SickSegment s = open(SickSeverity.MILD, NOW.minusHours(60), NOW.minusHours(47));
        assertThat(service.status(userId).active()).isTrue();
        assertThat(s.getEndedAt()).isNull();
    }

    @Test
    void statusIsInactiveWithNothingOpen() {
        assertThat(service.status(userId)).isEqualTo(SickModeStatusDTO.inactive());
    }

    @Test
    void confirmationIsDueOnTheFirstReadOfANewDay() {
        open(SickSeverity.MILD, NOW.minusHours(20), LocalDateTime.of(2026, 11, 15, 22, 0));
        assertThat(service.status(userId).confirmationDue()).isTrue();

        service.confirm(userId);

        assertThat(service.status(userId).confirmationDue()).isFalse();
    }

    @Test
    void confirmSetsLastConfirmedToNow() {
        SickSegment s = open(SickSeverity.MILD, NOW.minusHours(20), NOW.minusHours(20));
        service.confirm(userId);
        assertThat(s.getLastConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    void endClosesTheSegmentWithTheGivenReason() {
        SickSegment s = open(SickSeverity.MILD, NOW.minusHours(5), NOW.minusHours(5));
        service.end(userId, "MORNING_NO");
        assertThat(s.getEndedAt()).isEqualTo(NOW);
        assertThat(s.getEndReason()).isEqualTo("MORNING_NO");
    }

    @Test
    void endRejectsAnyOtherReason() {
        assertThatThrownBy(() -> service.end(userId, "LAPSED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.end(userId, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endWithNothingOpenReturnsInactive() {
        assertThat(service.end(userId, "USER")).isEqualTo(SickModeStatusDTO.inactive());
    }

    @Test
    void everyCommandEvictsTheCachedSettings() {
        service.start(userId, SickSeverity.MILD, Since.NOW);
        open(SickSeverity.MILD, NOW.minusHours(5), NOW.minusHours(5));
        service.changeSeverity(userId, SickSeverity.MODERATE);
        service.confirm(userId);
        service.end(userId, "USER");
        verify(userSettingsService, org.mockito.Mockito.times(4)).evict(userId);
    }

    @Test
    void lapseClosingEvictsTheCachedSettingsToo() {
        open(SickSeverity.MILD, NOW.minusHours(80), NOW.minusHours(50));
        service.status(userId);
        verify(userSettingsService).evict(userId);
    }

    @Test
    void statusReportsDayNumberAndPercentages() {
        LocalDateTime since = NOW.minusHours(30);
        open(SickSeverity.MODERATE, since, NOW.minusHours(1));

        SickModeStatusDTO status = service.status(userId);

        assertThat(status.active()).isTrue();
        assertThat(status.severity()).isEqualTo(SickSeverity.MODERATE);
        assertThat(status.since()).isEqualTo(since);
        assertThat(status.dayNumber()).isEqualTo(2);
        assertThat(status.isfPercent()).isEqualTo(-30);
        assertThat(status.basalNeedPercent()).isEqualTo(15);
        assertThat(status.stepUp()).isNull();
    }
}
