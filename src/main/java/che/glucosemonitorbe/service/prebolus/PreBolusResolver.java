package che.glucosemonitorbe.service.prebolus;

import che.glucosemonitorbe.entity.Note;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a pre-bolus is currently in flight for a prediction request.
 *
 * <p>A pure function of its arguments - no repository, no clock. Elapsed time is the
 * difference between two server-zone {@link LocalDateTime} values; no {@code ZoneId}
 * is ever consulted.</p>
 *
 * <p><b>Mirrors {@code PreBolusTimer.state()} in
 * {@code glucose-monitor-iphone/GlucoseMonitor/DashboardExtras.swift}.</b> The 2-hour
 * window, the meal aliases, and the set of labels that do not stop the timer are
 * duplicated from Swift. Any change there must be mirrored here; this class's tests
 * are the only guard against drift.</p>
 */
@Slf4j
@Component
public class PreBolusResolver {

    /** Meal labels marking a note as a pre-bolus. */
    static final Set<String> PRE_BOLUS_ALIASES  = Set.of("pre-bolus", "prebolus");
    /** Carb-bearing notes with these labels do NOT stop the timer. */
    static final Set<String> NON_STOPPING_MEALS = Set.of("correction", "pre-bolus", "prebolus");
    /** A pre-bolus older than this is treated as abandoned. */
    static final Duration MAX_AGE          = Duration.ofHours(2);
    /** Skew tolerated when matching an explicit insulinLoggedAt to a stored note. */
    static final Duration MATCH_TOLERANCE  = Duration.ofMinutes(1);

    public Optional<PreBolusContext> resolve(LocalDateTime insulinLoggedAt,
                                             List<Note> recentNotes,
                                             LocalDateTime now) {
        if (recentNotes == null || recentNotes.isEmpty()) {
            return Optional.empty();
        }
        if (insulinLoggedAt != null) {
            Optional<PreBolusContext> explicit = matchExplicit(insulinLoggedAt, recentNotes, now);
            if (explicit.isPresent()) {
                return explicit;
            }
            log.debug("insulinLoggedAt={} matched no bolus note; falling back to detection", insulinLoggedAt);
        }
        return detect(recentNotes, now);
    }

    private Optional<PreBolusContext> matchExplicit(LocalDateTime loggedAt, List<Note> notes, LocalDateTime now) {
        if (loggedAt.isAfter(now) || Duration.between(loggedAt, now).compareTo(MAX_AGE) >= 0) {
            return Optional.empty();
        }
        return notes.stream()
                .filter(this::isBolusNote)
                .filter(n -> Math.abs(Duration.between(loggedAt, n.getTimestamp()).toSeconds())
                             <= MATCH_TOLERANCE.toSeconds())
                .max(Comparator.comparing(Note::getTimestamp))
                .map(n -> toContext(n, now, PreBolusContext.Source.EXPLICIT));
    }

    private Optional<PreBolusContext> detect(List<Note> notes, LocalDateTime now) {
        Optional<Note> latest = notes.stream()
                .filter(this::isBolusNote)
                .filter(n -> PRE_BOLUS_ALIASES.contains(lower(n.getMeal())))
                .filter(n -> !n.getTimestamp().isAfter(now))
                .filter(n -> Duration.between(n.getTimestamp(), now).compareTo(MAX_AGE) < 0)
                .max(Comparator.comparing(Note::getTimestamp));

        if (latest.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime bolusTime = latest.get().getTimestamp();

        boolean mealAfter = notes.stream()
                .filter(n -> n.getTimestamp() != null)
                .filter(n -> n.getCarbs() != null && n.getCarbs() > 0)
                .filter(n -> !NON_STOPPING_MEALS.contains(lower(n.getMeal())))
                .anyMatch(n -> !n.getTimestamp().isBefore(bolusTime));

        return mealAfter
                ? Optional.empty()
                : Optional.of(toContext(latest.get(), now, PreBolusContext.Source.DETECTED));
    }

    private boolean isBolusNote(Note n) {
        return n.getTimestamp() != null
                && n.getInsulin() != null && n.getInsulin() > 0
                && !n.isLongActing();
    }

    private PreBolusContext toContext(Note n, LocalDateTime now, PreBolusContext.Source source) {
        return new PreBolusContext(
                n.getTimestamp(),
                n.getInsulin(),
                Math.max(0, (int) Duration.between(n.getTimestamp(), now).toMinutes()),
                source);
    }

    private String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
