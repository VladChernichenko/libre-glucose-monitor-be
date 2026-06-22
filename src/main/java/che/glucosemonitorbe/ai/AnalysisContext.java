package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisContext {
    private UUID userId;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private List<Double> glucoseValues;
    private List<Long> glucoseTimestamps;
    private List<Note> notes;
    private UserSettingsDTO userSettings;
    private UserInsulinPreferencesDTO insulinPreferences;
    private double minGlucose;
    private double maxGlucose;
    private double avgGlucose;
    private double latestGlucose;
    private double deltaGlucose;
    private Double activeCob;
    private Double activeIob;
    private Double predictedGlucose2h;
    private Double estimatedCorrectionUnits;
    private Double avgPreBolusPauseMinutes;
    private Double latestPreBolusPauseMinutes;
    private Double preBolusTimingContribution;
}
