package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.entity.Note;
import lombok.*;

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
    private COBSettingsDTO cobSettings;
    private UserInsulinPreferencesDTO insulinPreferences;
    private double minGlucose;
    private double maxGlucose;
    private double avgGlucose;
    private double latestGlucose;
    private double deltaGlucose;
}
