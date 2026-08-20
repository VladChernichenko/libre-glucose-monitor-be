package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.entity.Note;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NoteToCarbsEntryMapperRescueTest {

    private FeatureToggleConfig config;
    private NoteToCarbsEntryMapper mapper;

    @BeforeEach
    void setUp() {
        config = new FeatureToggleConfig();
        config.setNutritionAwarePredictionEnabled(true);
        mapper = new NoteToCarbsEntryMapper(config, new ObjectMapper());
    }

    @Test
    void hypoTreatmentNoteMapsToRescueAbsorptionMode() {
        CarbsEntry entry = mapper.toCarbsEntry(rescueNote());
        assertThat(entry.getAbsorptionMode()).isEqualTo(RescueCarbProfile.ABSORPTION_MODE);
        assertThat(entry.getEstimatedGi()).isEqualTo((double) RescueCarbProfile.GI);
    }

    /** The rescue curve is physiology, not a nutrition-analysis nicety - the flag must not gate it. */
    @Test
    void rescueModeSurvivesNutritionAwarePredictionBeingOff() {
        config.setNutritionAwarePredictionEnabled(false);
        CarbsEntry entry = mapper.toCarbsEntry(rescueNote());
        assertThat(entry.getAbsorptionMode()).isEqualTo(RescueCarbProfile.ABSORPTION_MODE);
    }

    /** A stored nutrition profile must not be able to slow a rescue carb back down. */
    @Test
    void rescueModeIsNotOverriddenByAStoredNutritionProfile() {
        Note note = rescueNote();
        note.setNutritionProfile("{\"absorptionMode\":\"GI_GL_ENHANCED\",\"estimatedGi\":35.0}");
        CarbsEntry entry = mapper.toCarbsEntry(note);
        assertThat(entry.getAbsorptionMode()).isEqualTo(RescueCarbProfile.ABSORPTION_MODE);
    }

    @Test
    void ordinaryNoteIsUnaffected() {
        Note note = new Note(UUID.randomUUID(), LocalDateTime.now(), 40.0, 4.0, "Lunch");
        CarbsEntry entry = mapper.toCarbsEntry(note);
        assertThat(entry.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
    }

    private Note rescueNote() {
        Note note = new Note(UUID.randomUUID(), LocalDateTime.now(), 15.0, 0.0, "Hypo treatment");
        note.setType(Note.TYPE_HYPO_TREATMENT);
        return note;
    }
}
