package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.ActivityIntensity;
import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the intensity->a(t) mapping and the notes-derived activity provider. */
class NotesActivityProviderTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 7, 5, 12, 0);

    @Test
    @DisplayName("intensity levels map to a(t) = 0.25 / 0.5 / 0.75 / 1.0")
    void intensityMapping() {
        assertThat(ActivityIntensity.LOW.aLevel()).isEqualTo(0.25);
        assertThat(ActivityIntensity.MODERATE.aLevel()).isEqualTo(0.5);
        assertThat(ActivityIntensity.HIGH.aLevel()).isEqualTo(0.75);
        assertThat(ActivityIntensity.VERY_HARD.aLevel()).isEqualTo(1.0);
        assertThat(ActivityIntensity.fromName("moderate")).isEqualTo(ActivityIntensity.MODERATE);
        assertThat(ActivityIntensity.fromName("nonsense")).isNull();
    }

    @Test
    @DisplayName("no activity notes ⇒ ActivityProvider.NONE (model unchanged)")
    void noActivity_isNone() {
        assertThat(NotesActivityProvider.fromNotes(null)).isSameAs(ActivityProvider.NONE);
        assertThat(NotesActivityProvider.fromNotes(List.of())).isSameAs(ActivityProvider.NONE);
        assertThat(NotesActivityProvider.fromNotes(List.of(meal()))).isSameAs(ActivityProvider.NONE);
    }

    @Test
    @DisplayName("a(t) is the level inside the window, 0 outside and at the end boundary")
    void windowBounds() {
        ActivityProvider p = NotesActivityProvider.fromNotes(List.of(activity("MODERATE", 60)));
        assertThat(p.intensityAt(T0.minusMinutes(1))).isZero();
        assertThat(p.intensityAt(T0)).isEqualTo(0.5);
        assertThat(p.intensityAt(T0.plusMinutes(59))).isEqualTo(0.5);
        assertThat(p.intensityAt(T0.plusMinutes(60))).isZero();   // end is exclusive
    }

    @Test
    @DisplayName("overlapping activities ⇒ the maximum level, not the sum")
    void overlapTakesMax() {
        ActivityProvider p = NotesActivityProvider.fromNotes(List.of(
                activity("MODERATE", 60), activityAt(T0.plusMinutes(10), "HIGH", 30)));
        assertThat(p.intensityAt(T0.plusMinutes(20))).isEqualTo(0.75);   // both cover -> max
        assertThat(p.intensityAt(T0.plusMinutes(50))).isEqualTo(0.5);    // only moderate covers
    }

    @Test
    @DisplayName("per-user gain scales the intensity and is clamped to [0,1]")
    void gainScalesAndClamps() {
        assertThat(NotesActivityProvider.fromNotes(List.of(activity("MODERATE", 60)), 1.5)
                .intensityAt(T0)).isEqualTo(0.75);
        assertThat(NotesActivityProvider.fromNotes(List.of(activity("HIGH", 60)), 2.0)
                .intensityAt(T0)).isEqualTo(1.0);   // 0.75 * 2 clamped to 1
    }

    // -- helpers ----------------------------------------------------------------

    private static Note activity(String intensity, int durationMin) {
        return activityAt(T0, intensity, durationMin);
    }

    private static Note activityAt(LocalDateTime start, String intensity, int durationMin) {
        Note n = new Note();
        n.setType(Note.TYPE_ACTIVITY);
        n.setActivityType("RUNNING");
        n.setIntensity(intensity);
        n.setDurationMin(durationMin);
        n.setTimestamp(start);
        return n;
    }

    private static Note meal() {
        Note n = new Note();
        n.setType(Note.TYPE_NORMAL);
        n.setCarbs(40.0);
        n.setTimestamp(T0);
        return n;
    }
}
