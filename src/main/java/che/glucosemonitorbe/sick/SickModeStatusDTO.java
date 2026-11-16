package che.glucosemonitorbe.sick;

import java.time.LocalDateTime;
import java.util.UUID;

/** Current sick-mode state. When inactive every field except {@code active} and {@code confirmationDue} is null/false. */
public record SickModeStatusDTO(boolean active, SickSeverity severity, LocalDateTime since, Integer dayNumber,
                                Integer isfPercent, Integer basalNeedPercent, boolean confirmationDue,
                                StepUp stepUp) {

    /** A pending proposal to move to the next severity; {@code toSeverity} is null at the top level. */
    public record StepUp(UUID noteId, SickSeverity toSeverity, boolean showHelpLine) {}

    public static SickModeStatusDTO inactive() {
        return new SickModeStatusDTO(false, null, null, null, null, null, false, null);
    }
}
