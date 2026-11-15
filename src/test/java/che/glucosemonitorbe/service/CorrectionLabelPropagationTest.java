package che.glucosemonitorbe.service;

import static org.assertj.core.api.Assertions.assertThat;

import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The replay engine never sees the {@code Note}, so the user's "Correction" label has to travel on
 * the flattened event: it is what lets the calibrator fit insulin sensitivity on corrections only.
 */
class CorrectionLabelPropagationTest {

    @Test
    void aCarbFreeBolusLabelledCorrectionIsMarked() {
        assertThat(event("Correction", 0.0, 2.0, Note.TYPE_NORMAL).correction()).isTrue();
        assertThat(event("correction", 0.0, 2.0, Note.TYPE_NORMAL).correction()).isTrue();
    }

    @Test
    void otherLabelsAreNotCorrections() {
        assertThat(event("Pre-bolus", 0.0, 2.0, Note.TYPE_NORMAL).correction()).isFalse();
        assertThat(event("Dinner", 0.0, 2.0, Note.TYPE_NORMAL).correction()).isFalse();
        assertThat(event(null, 0.0, 2.0, Note.TYPE_NORMAL).correction()).isFalse();
    }

    @Test
    void theLabelAloneIsNotEnough() {
        assertThat(event("Correction", 20.0, 2.0, Note.TYPE_NORMAL).correction())
                .as("carbs on the note make it a meal bolus").isFalse();
        assertThat(event("Correction", 0.0, 0.0, Note.TYPE_NORMAL).correction())
                .as("no insulin, nothing to correct with").isFalse();
        assertThat(event("Correction", 0.0, 18.0, Note.TYPE_LONG_ACTING).correction())
                .as("a basal dose is never a correction").isFalse();
    }

    private static PredictionReplayEngine.Event event(String meal, double carbs, double insulin, String type) {
        Note note = new Note();
        note.setTimestamp(LocalDateTime.of(2026, 11, 15, 19, 12));
        note.setMeal(meal);
        note.setCarbs(carbs);
        note.setInsulin(insulin);
        note.setType(type);
        return DigitalTwinCalibrationService.toEvent(note, ZoneOffset.UTC);
    }
}
