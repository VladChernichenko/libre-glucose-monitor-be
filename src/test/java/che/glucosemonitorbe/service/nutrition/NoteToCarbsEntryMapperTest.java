package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.entity.Note;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NoteToCarbsEntryMapperTest {

    private FeatureToggleConfig    toggles;
    private NoteToCarbsEntryMapper sut;

    @BeforeEach
    void setUp() {
        toggles = new FeatureToggleConfig();
        toggles.setNutritionAwarePredictionEnabled(true);
        sut = new NoteToCarbsEntryMapper(toggles, new ObjectMapper());
    }

    private Note note(String nutritionProfile) {
        Note n = new Note();
        n.setId(UUID.randomUUID());
        n.setUserId(UUID.randomUUID());
        n.setTimestamp(LocalDateTime.of(2026, 7, 31, 12, 0));
        n.setCarbs(45.0);
        n.setInsulin(4.0);
        n.setMeal("lunch");
        n.setNutritionProfile(nutritionProfile);
        return n;
    }

    @Test
    @DisplayName("hydrates macros and glycemic fields from nutrition_profile")
    void hydratesMacros() {
        CarbsEntry entry = sut.toCarbsEntry(
                note("{\"estimatedGi\":42.0,\"glycemicLoad\":18.9,\"fiber\":6.0,"
                   + "\"protein\":22.0,\"fat\":15.0}"));

        assertThat(entry.getEstimatedGi()).isEqualTo(42.0);
        assertThat(entry.getGlycemicLoad()).isEqualTo(18.9);
        assertThat(entry.getFiber()).isEqualTo(6.0);
        assertThat(entry.getProtein()).isEqualTo(22.0);
        assertThat(entry.getFat()).isEqualTo(15.0);
        assertThat(entry.getCarbs()).isEqualTo(45.0);
        assertThat(entry.getMealType()).isEqualTo("lunch");
    }

    @Test
    @DisplayName("malformed nutrition_profile falls back to DEFAULT_DECAY without throwing")
    void malformedJson() {
        CarbsEntry entry = sut.toCarbsEntry(note("{not json"));

        assertThat(entry.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
        assertThat(entry.getProtein()).isNull();
        assertThat(entry.getCarbs()).isEqualTo(45.0);
    }

    @Test
    @DisplayName("nutrition toggle off leaves macros unhydrated")
    void toggleOff() {
        toggles.setNutritionAwarePredictionEnabled(false);

        CarbsEntry entry = sut.toCarbsEntry(
                note("{\"estimatedGi\":42.0,\"protein\":22.0}"));

        assertThat(entry.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
        assertThat(entry.getProtein()).isNull();
        assertThat(entry.getEstimatedGi()).isNull();
    }

    @Test
    @DisplayName("null nutrition_profile yields a bare entry")
    void nullProfile() {
        CarbsEntry entry = sut.toCarbsEntry(note(null));

        assertThat(entry.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
        assertThat(entry.getProtein()).isNull();
    }
}
