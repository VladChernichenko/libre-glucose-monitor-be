package che.glucosemonitorbe.service.prebolus;

import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PreBolusResolverTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 31, 12, 0);

    private PreBolusResolver sut;

    @BeforeEach
    void setUp() {
        sut = new PreBolusResolver();
    }

    private Note note(String meal, double insulin, double carbs, LocalDateTime at) {
        Note n = new Note();
        n.setId(UUID.randomUUID());
        n.setUserId(UUID.randomUUID());
        n.setTimestamp(at);
        n.setMeal(meal);
        n.setInsulin(insulin);
        n.setCarbs(carbs);
        n.setType(Note.TYPE_NORMAL);
        return n;
    }

    // -- Ported from PreBolusTimerTests.swift ---------------------------------

    @Test
    @DisplayName("no notes -> no context")
    void noNotes() {
        assertThat(sut.resolve(null, List.of(), NOW)).isEmpty();
    }

    @Test
    @DisplayName("pre-bolus note with zero insulin -> no context")
    void zeroInsulin() {
        List<Note> notes = List.of(note("pre-bolus", 0.0, 0.0, NOW.minusMinutes(5)));
        assertThat(sut.resolve(null, notes, NOW)).isEmpty();
    }

    @Test
    @DisplayName("pre-bolus note with null timestamp -> no context")
    void nullTimestamp() {
        assertThat(sut.resolve(null, List.of(note("pre-bolus", 3.0, 0.0, null)), NOW)).isEmpty();
    }

    @Test
    @DisplayName("meal logged after the pre-bolus -> no context")
    void mealAfterPreBolus() {
        List<Note> notes = List.of(
                note("pre-bolus", 4.0, 0.0, NOW.minusMinutes(30)),
                note("lunch",     0.0, 50.0, NOW.minusMinutes(10)));
        assertThat(sut.resolve(null, notes, NOW)).isEmpty();
    }

    @Test
    @DisplayName("a correction note after the pre-bolus does not stop the timer")
    void correctionDoesNotStop() {
        List<Note> notes = List.of(
                note("pre-bolus",  4.0, 0.0,  NOW.minusMinutes(30)),
                note("correction", 2.0, 10.0, NOW.minusMinutes(10)));
        assertThat(sut.resolve(null, notes, NOW)).isPresent();
    }

    @Test
    @DisplayName("plain pre-bolus note -> context with elapsed minutes")
    void plainPreBolus() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(12)));

        PreBolusContext ctx = sut.resolve(null, notes, NOW).orElseThrow();

        assertThat(ctx.elapsedMinutes()).isEqualTo(12);
        assertThat(ctx.units()).isEqualTo(3.0);
        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.DETECTED);
    }

    @Test
    @DisplayName("prebolus alias and mixed case both resolve")
    void aliasAndCase() {
        assertThat(sut.resolve(null, List.of(note("prebolus",  3.0, 0.0, NOW.minusMinutes(5))), NOW)).isPresent();
        assertThat(sut.resolve(null, List.of(note("Pre-Bolus", 3.0, 0.0, NOW.minusMinutes(5))), NOW)).isPresent();
    }

    @Test
    @DisplayName("pre-bolus older than 2 hours has expired")
    void expiredAfterTwoHours() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(121)));
        assertThat(sut.resolve(null, notes, NOW)).isEmpty();
    }

    // -- Backend-only cases ---------------------------------------------------

    @Test
    @DisplayName("long-acting note is never a pre-bolus")
    void longActingExcluded() {
        Note n = note("pre-bolus", 20.0, 0.0, NOW.minusMinutes(10));
        n.setType(Note.TYPE_LONG_ACTING);
        assertThat(sut.resolve(null, List.of(n), NOW)).isEmpty();
    }

    @Test
    @DisplayName("latest of several pre-bolus notes wins")
    void latestWins() {
        List<Note> notes = List.of(
                note("pre-bolus", 2.0, 0.0, NOW.minusMinutes(50)),
                note("pre-bolus", 5.0, 0.0, NOW.minusMinutes(8)));

        PreBolusContext ctx = sut.resolve(null, notes, NOW).orElseThrow();

        assertThat(ctx.units()).isEqualTo(5.0);
        assertThat(ctx.elapsedMinutes()).isEqualTo(8);
    }

    @Test
    @DisplayName("explicit insulinLoggedAt matches a bolus note without the pre-bolus label")
    void explicitMatch() {
        LocalDateTime at = NOW.minusMinutes(15);
        List<Note> notes = List.of(note("dinner", 6.0, 0.0, at));

        PreBolusContext ctx = sut.resolve(at, notes, NOW).orElseThrow();

        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.EXPLICIT);
        assertThat(ctx.units()).isEqualTo(6.0);
        assertThat(ctx.elapsedMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("explicit insulinLoggedAt tolerates a 1-minute skew")
    void explicitTolerance() {
        List<Note> notes = List.of(note("dinner", 6.0, 0.0, NOW.minusMinutes(15)));
        assertThat(sut.resolve(NOW.minusMinutes(15).plusSeconds(40), notes, NOW)).isPresent();
    }

    @Test
    @DisplayName("explicit insulinLoggedAt in the future falls back to detection")
    void explicitFutureFallsBack() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(10)));

        PreBolusContext ctx = sut.resolve(NOW.plusMinutes(5), notes, NOW).orElseThrow();

        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.DETECTED);
    }

    @Test
    @DisplayName("explicit insulinLoggedAt matching no note falls back to detection")
    void explicitUnmatchedFallsBack() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(10)));

        PreBolusContext ctx = sut.resolve(NOW.minusMinutes(47), notes, NOW).orElseThrow();

        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.DETECTED);
    }

    @Test
    @DisplayName("elapsed minutes come from the two LocalDateTime arguments and nothing else")
    void elapsedIsZoneFree() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(23)));
        assertThat(sut.resolve(null, notes, NOW).orElseThrow().elapsedMinutes()).isEqualTo(23);
    }

    @Test
    @DisplayName("exactly 120 minutes (2-hour boundary) is expired")
    void boundaryExactly120Minutes() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(120)));
        assertThat(sut.resolve(null, notes, NOW)).isEmpty();
    }

    @Test
    @DisplayName("119 minutes is still valid")
    void boundaryLastValidMinute() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(119)));
        PreBolusContext ctx = sut.resolve(null, notes, NOW).orElseThrow();
        assertThat(ctx.elapsedMinutes()).isEqualTo(119);
    }

    @Test
    @DisplayName("explicit insulinLoggedAt older than 2 hours is rejected")
    void explicitOlderThan2Hours() {
        List<Note> notes = List.of(note("dinner", 6.0, 0.0, NOW.minusMinutes(140)));
        assertThat(sut.resolve(NOW.minusMinutes(140), notes, NOW)).isEmpty();
    }

    /**
     * Pins {@code matchExplicit}'s own max-age boundary, mirroring
     * {@link #boundaryExactly120Minutes()} / {@link #boundaryLastValidMinute()} for
     * {@code detect}. {@link #explicitOlderThan2Hours()} at 140 minutes is rejected by
     * both {@code >= MAX_AGE} and a loosened {@code > MAX_AGE}, so it cannot catch a
     * regression of the comparator - only the exact-120 case can.
     */
    @Test
    @DisplayName("explicit insulinLoggedAt at exactly 120 minutes (2-hour boundary) is rejected")
    void explicitBoundaryExactly120Minutes() {
        LocalDateTime at = NOW.minusMinutes(120);
        List<Note> notes = List.of(note("dinner", 6.0, 0.0, at));
        assertThat(sut.resolve(at, notes, NOW)).isEmpty();
    }

    @Test
    @DisplayName("explicit insulinLoggedAt at 119 minutes is still valid")
    void explicitBoundaryLastValidMinute() {
        LocalDateTime at = NOW.minusMinutes(119);
        List<Note> notes = List.of(note("dinner", 6.0, 0.0, at));

        PreBolusContext ctx = sut.resolve(at, notes, NOW).orElseThrow();

        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.EXPLICIT);
        assertThat(ctx.elapsedMinutes()).isEqualTo(119);
    }

    @Test
    @DisplayName("note timestamped after now clamps negative elapsed to zero")
    void negativeElapsedClamped() {
        // 60 seconds is the largest future skew that still matches: matchExplicit rejects a
        // loggedAt after `now`, and the note must sit within MATCH_TOLERANCE (1 min) of it.
        // So loggedAt == NOW and the note at NOW + 60s. Duration.between(note, now) is then
        // exactly -1 minute, and Math.max(0, ...) is what turns it into 0 - a smaller skew
        // (e.g. 30s) truncates to 0 on its own and would pass with or without the clamp.
        LocalDateTime noteTime = NOW.plusSeconds(60);
        List<Note> notes = List.of(note("dinner", 6.0, 0.0, noteTime));

        PreBolusContext ctx = sut.resolve(NOW, notes, NOW).orElseThrow();

        assertThat(ctx.elapsedMinutes())
                .as("raw elapsed is -1 min; the clamp must floor it at 0")
                .isEqualTo(0);
        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.EXPLICIT);
    }
}
