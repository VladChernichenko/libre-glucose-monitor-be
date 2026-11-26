package che.glucosemonitorbe.assistant.advice;

import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MealContextTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 20, 0);

    private static Note note(LocalDateTime t, double carbs, double insulin, String meal) {
        return new Note(USER, t, carbs, insulin, meal);
    }

    private static Note rescue(LocalDateTime t, double carbs) {
        Note n = note(t, carbs, 0, "Hypo treatment");
        n.setType(Note.TYPE_HYPO_TREATMENT);
        return n;
    }

    @Test
    void carbNoteWithin4hIsMealContext() {
        assertThat(MealContext.at(NOW, List.of(note(NOW.minusHours(4).plusMinutes(1), 10, 0, "Lunch")))).isTrue();
    }

    @Test
    void carbNoteOlderThan4hIsNot() {
        assertThat(MealContext.at(NOW, List.of(note(NOW.minusHours(4), 40, 0, "Lunch")))).isFalse();
        assertThat(MealContext.at(NOW, List.of(note(NOW.minusHours(1), 9.9, 0, "Snack")))).isFalse();
    }

    @Test
    void rescueCarbsAloneAreNot() {
        assertThat(MealContext.at(NOW, List.of(rescue(NOW.minusMinutes(20), 30)))).isFalse();
    }

    @Test
    void preBolusWithoutCarbsWithin60IsMealContext() {
        assertThat(MealContext.at(NOW, List.of(note(NOW.minusMinutes(59), 0, 4, "Pre-bolus")))).isTrue();
        assertThat(MealContext.at(NOW, List.of(note(NOW.minusMinutes(60), 0, 4, "Pre-bolus")))).isFalse();
    }

    @Test
    void correctionDoseIsNot() {
        assertThat(MealContext.at(NOW, List.of(note(NOW.minusMinutes(10), 0, 2, "correction")))).isFalse();
    }

    @Test
    void preBolusFollowedByCarbsIsMealContextThroughTheCarbs() {
        List<Note> notes = List.of(note(NOW.minusMinutes(90), 0, 4, "Pre-bolus"),
                note(NOW.minusMinutes(75), 40, 0, "Dinner"));
        assertThat(MealContext.at(NOW, notes)).isTrue();
        // The carbs (75 min ago) carry the context to 4 h after them, long past the pre-bolus hour.
        assertThat(MealContext.at(NOW.plusMinutes(150), notes)).isTrue();
        assertThat(MealContext.at(NOW.plusMinutes(170), notes)).isFalse();
    }

    @Test
    void futureNotesAreIgnored() {
        assertThat(MealContext.at(NOW, List.of(note(NOW.plusMinutes(5), 40, 0, "Dinner"),
                note(NOW.plusMinutes(1), 0, 4, "Pre-bolus")))).isFalse();
    }
}
