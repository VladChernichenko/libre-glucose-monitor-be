package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.entity.Note;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Maps a {@link Note} to a {@link CarbsEntry}, hydrating macronutrient and glycemic
 * fields from the note's {@code nutrition_profile} JSON.
 *
 * <p>Extracted from {@code GlucoseCalculationsService.convertNoteToCarbsEntry} so the
 * Hovorka prediction path gets the same hydration the legacy path has always had.
 * Behaviour is intentionally identical to the original.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoteToCarbsEntryMapper {

    private final FeatureToggleConfig featureToggleConfig;
    private final ObjectMapper        objectMapper;

    public CarbsEntry toCarbsEntry(Note note) {
        CarbsEntry entry = CarbsEntry.builder()
            .id(note.getId())
            .timestamp(note.getTimestamp())
            .carbs(note.getCarbs())
            .insulin(note.getInsulin() != null ? note.getInsulin() : 0.0)
            .mealType(note.getMeal())
            .comment(note.getComment())
            .glucoseValue(note.getGlucoseLevel())
            .originalCarbs(note.getCarbs())
            .userId(note.getUserId())
            .build();
        entry.setAbsorptionMode(note.getAbsorptionMode() != null ? note.getAbsorptionMode() : "DEFAULT_DECAY");
        if (!featureToggleConfig.isNutritionAwarePredictionEnabled()) {
            entry.setAbsorptionMode("DEFAULT_DECAY");
            return entry;
        }
        if (note.getNutritionProfile() != null && !note.getNutritionProfile().isBlank()) {
            try {
                NutritionSnapshot snapshot = objectMapper.readValue(note.getNutritionProfile(), NutritionSnapshot.class);
                entry.setEstimatedGi(snapshot.getEstimatedGi());
                entry.setGlycemicLoad(snapshot.getGlycemicLoad());
                entry.setFiber(snapshot.getFiber());
                entry.setProtein(snapshot.getProtein());
                entry.setFat(snapshot.getFat());
                entry.setAbsorptionSpeedClass(snapshot.getAbsorptionSpeedClass());
                if (snapshot.getAbsorptionMode() != null) {
                    entry.setAbsorptionMode(snapshot.getAbsorptionMode());
                }
                entry.setBolusStrategy(snapshot.getBolusStrategy());
                entry.setSuggestedDurationHours(snapshot.getSuggestedDurationHours());
                entry.setPatternName(snapshot.getPatternName());
            } catch (Exception e) {
                log.warn("Failed to parse nutritionProfile for note {}: {}", note.getId(), e.getMessage());
                entry.setAbsorptionMode("DEFAULT_DECAY");
            }
        }
        return entry;
    }
}
