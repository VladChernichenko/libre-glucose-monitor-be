package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FeatureToggleService {
    
    private final FeatureToggleConfig config;
    
    /**
     * Check if a specific feature should use the new backend
     */
    public boolean shouldUseBackend(String featureName) {
        if (!config.isBackendModeEnabled()) {
            return false;
        }
        
        return switch (featureName.toLowerCase()) {
            case "insulin-calculator" -> config.isInsulinCalculatorEnabled();
            case "carbs-on-board" -> config.isCarbsOnBoardEnabled();
            case "glucose-data" -> config.isGlucoseDataEnabled();
            case "user-configuration" -> config.isUserConfigurationEnabled();
            default -> false;
        };
    }
    
    /**
     * Check if a feature should be gradually migrated (percentage-based)
     */
    public boolean shouldMigrate(String featureName, String userId) {
        if (!config.isBackendModeEnabled()) {
            return false;
        }
        
        int migrationPercent = getMigrationPercent(featureName);
        if (migrationPercent == 0) return false;
        if (migrationPercent == 100) return true;
        
        // Simple hash-based distribution for gradual migration
        int userHash = Math.abs(userId.hashCode());
        return (userHash % 100) < migrationPercent;
    }
    
    /**
     * Get migration percentage for a feature
     */
    public int getMigrationPercent(String featureName) {
        return switch (featureName.toLowerCase()) {
            case "insulin-calculator" -> config.getInsulinCalculatorMigrationPercent();
            case "carbs-on-board" -> config.getCarbsOnBoardMigrationPercent();
            case "glucose-data" -> config.getGlucoseDataMigrationPercent();
            default -> 0;
        };
    }
    
    /**
     * Check if backend mode is globally enabled
     */
    public boolean isBackendModeEnabled() {
        return config.isBackendModeEnabled();
    }
}
