package che.glucosemonitorbe.sick;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SickProfileTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 10, 8, 0);

    private static SickProfile.Span span(LocalDateTime from, LocalDateTime endedAt, LocalDateTime confirmed,
                                         SickSeverity s) {
        return new SickProfile.Span(from, endedAt, confirmed, s);
    }

    @Test
    void multiplierFollowsTheSegmentInForceAtThatTime() {
        LocalDateTime change = T0.plusHours(10);
        LocalDateTime end = T0.plusHours(20);
        SickProfile p = new SickProfile(List.of(
                span(T0, change, change, SickSeverity.MODERATE),
                span(change, end, end, SickSeverity.SEVERE)), null);

        assertThat(p.isfMultiplierAt(T0.minusMinutes(1))).isEqualTo(1.0);
        assertThat(p.isfMultiplierAt(T0.plusHours(5))).isEqualTo(0.70);
        assertThat(p.isfMultiplierAt(change.plusHours(1))).isEqualTo(0.50);
        assertThat(p.isfMultiplierAt(end.plusHours(1))).isEqualTo(1.0);
    }

    @Test
    void endIsExclusive() {
        LocalDateTime end = T0.plusHours(10);
        SickProfile p = new SickProfile(List.of(span(T0, end, end, SickSeverity.MODERATE)), null);

        assertThat(p.isfMultiplierAt(end.minusSeconds(1))).isEqualTo(0.70);
        assertThat(p.isfMultiplierAt(end)).isEqualTo(1.0);
    }

    @Test
    void anOpenSpanLapses48hAfterItsLastConfirmation() {
        LocalDateTime confirmed = T0.plusHours(2);
        SickProfile p = new SickProfile(List.of(span(T0, null, confirmed, SickSeverity.MODERATE)), null);

        assertThat(p.isfMultiplierAt(confirmed.plusHours(47).plusMinutes(59))).isEqualTo(0.70);
        assertThat(p.isfMultiplierAt(confirmed.plusHours(48))).isEqualTo(1.0);
    }

    @Test
    void learningIsExcludedForSixHoursAfterTheEnd() {
        LocalDateTime end = T0.plusHours(10);
        SickProfile p = new SickProfile(List.of(span(T0, end, end, SickSeverity.MILD)), null);

        assertThat(p.excludedFromLearning(T0.minusMinutes(1))).isFalse();
        assertThat(p.excludedFromLearning(T0.plusHours(3))).isTrue();
        assertThat(p.excludedFromLearning(end.plusHours(5).plusMinutes(59))).isTrue();
        assertThat(p.excludedFromLearning(end.plusHours(6))).isFalse();
    }

    @Test
    void basalReferenceUsesTheFrozenHealthyAmount() {
        LocalDateTime end = T0.plusHours(10);
        List<SickProfile.Span> spans = List.of(span(T0, end, end, SickSeverity.MODERATE));
        SickProfile p = new SickProfile(spans, 20.0);

        assertThat(p.basalReferenceUnits(17.0, T0.plusHours(1))).isCloseTo(23.0, within(1e-9));
        assertThat(p.basalReferenceUnits(17.0, end.plusHours(55))).isEqualTo(20.0);
        assertThat(p.basalReferenceUnits(17.0, end.plusHours(57))).isEqualTo(17.0);
        assertThat(new SickProfile(spans, null).basalReferenceUnits(17.0, T0.plusHours(1))).isEqualTo(17.0);
    }

    @Test
    void unresponsiveIsNotSelectableAndHasNoNextLevel() {
        assertThat(SickSeverity.SEVERE_UNRESPONSIVE.selectable()).isFalse();
        assertThat(SickSeverity.SEVERE_UNRESPONSIVE.next()).isEmpty();
        assertThat(SickSeverity.MILD.next()).contains(SickSeverity.MODERATE);
        assertThat(SickSeverity.SEVERE.next()).contains(SickSeverity.SEVERE_UNRESPONSIVE);
        assertThat(SickSeverity.SEVERE.selectable()).isTrue();
        assertThat(SickSeverity.SEVERE_UNRESPONSIVE.isfMultiplier()).isEqualTo(0.40);
        assertThat(SickSeverity.SEVERE_UNRESPONSIVE.basalNeed()).isEqualTo(0.30);
    }

    @Test
    void emptyProfileChangesNothing() {
        assertThat(SickProfile.EMPTY.severityAt(T0)).isEmpty();
        assertThat(SickProfile.EMPTY.isfMultiplierAt(T0)).isEqualTo(1.0);
        assertThat(SickProfile.EMPTY.basalNeedAt(T0)).isEqualTo(0.0);
        assertThat(SickProfile.EMPTY.excludedFromLearning(T0)).isFalse();
        assertThat(SickProfile.EMPTY.basalReferenceUnits(17.0, T0)).isEqualTo(17.0);
    }
}
