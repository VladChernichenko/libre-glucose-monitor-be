package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FeatureToggleService {
    
    private final FeatureToggleConfig config;
    
    /**
     * Check if a specific feature should use the new backend
     */
    public boolean shouldUseBackend(String featureName) {
        return switch (featureName.toLowerCase()) {
            case "glucose-calculations" -> config.isGlucoseCalculationsEnabled();
            default -> false;
        };
    }
    
    /**
     * Check if a named feature toggle is on.
     * Falls back to shouldUseBackend() for legacy feature names.
     */
    public boolean isEnabled(String featureName) {
        return switch (featureName.toLowerCase()) {
            case "digital-twin-enabled"        -> config.isDigitalTwinEnabled();
            case "glycemic-event-detection-enabled" -> config.isGlycemicEventDetectionEnabled();
            case "hypo-rescue-logging-enabled"  -> config.isHypoRescueLoggingEnabled();
            case "activity-logging-enabled"    -> config.isActivityLoggingEnabled();
            case "empirical-twin-enabled"      -> config.isEmpiricalTwinEnabled();
            case "history-dose-enabled"        -> config.isHistoryDoseEnabled();
            default                            -> shouldUseBackend(featureName);
        };
    }
}
