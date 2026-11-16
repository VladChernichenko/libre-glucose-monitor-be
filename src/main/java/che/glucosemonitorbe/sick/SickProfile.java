package che.glucosemonitorbe.sick;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Time-based illness profile: spans of sickness with severity, plus the frozen healthy basal amount. */
public record SickProfile(List<Span> spans, Double healthyBasalUnits) {

    public static final int LAPSE_HOURS = 48;
    public static final int LEARNING_TAIL_HOURS = 6;
    public static final int BASAL_HOLD_HOURS = 56;
    public static final SickProfile EMPTY = new SickProfile(List.of(), null);

    public SickProfile {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }

    public record Span(LocalDateTime from, LocalDateTime endedAt, LocalDateTime lastConfirmedAt,
                       SickSeverity severity) {

        public LocalDateTime effectiveEnd() {
            LocalDateTime lapse = lastConfirmedAt.plusHours(LAPSE_HOURS);
            return endedAt != null && endedAt.isBefore(lapse) ? endedAt : lapse;
        }

        boolean covers(LocalDateTime t) {
            return !t.isBefore(from) && t.isBefore(effectiveEnd());
        }
    }

    public Optional<SickSeverity> severityAt(LocalDateTime t) {
        return spans.stream().filter(s -> s.covers(t)).map(Span::severity).findFirst();
    }

    public double isfMultiplierAt(LocalDateTime t) {
        return severityAt(t).map(SickSeverity::isfMultiplier).orElse(1.0);
    }

    public double basalNeedAt(LocalDateTime t) {
        return severityAt(t).map(SickSeverity::basalNeed).orElse(0.0);
    }

    public boolean excludedFromLearning(LocalDateTime t) {
        return spans.stream().anyMatch(s -> !t.isBefore(s.from())
                && t.isBefore(s.effectiveEnd().plusHours(LEARNING_TAIL_HOURS)));
    }

    public double basalReferenceUnits(double liveUsual, LocalDateTime t) {
        if (healthyBasalUnits == null) {
            return liveUsual;
        }
        if (severityAt(t).isPresent()) {
            return healthyBasalUnits * (1 + basalNeedAt(t));
        }
        boolean held = spans.stream().map(Span::effectiveEnd).max(Comparator.naturalOrder())
                .map(end -> !t.isBefore(end) && t.isBefore(end.plusHours(BASAL_HOLD_HOURS)))
                .orElse(false);
        return held ? healthyBasalUnits : liveUsual;
    }
}
