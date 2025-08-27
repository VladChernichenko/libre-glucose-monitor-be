package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.service.FeatureToggleService;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.Map;

@RestController
@RequestMapping("/api/features")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000"}, allowCredentials = "true")
public class FeatureToggleController {
    
    private final FeatureToggleService featureToggleService;
    private final FeatureToggleConfig featureToggleConfig;
    
    @GetMapping("/status")
    public Map<String, Object> getFeatureStatus() {
        return Map.of(
            "backendModeEnabled", featureToggleService.isBackendModeEnabled(),
            "insulinCalculator", Map.of(
                "enabled", featureToggleConfig.isInsulinCalculatorEnabled(),
                "migrationPercent", featureToggleConfig.getInsulinCalculatorMigrationPercent()
            ),
            "carbsOnBoard", Map.of(
                "enabled", featureToggleConfig.isCarbsOnBoardEnabled(),
                "migrationPercent", featureToggleConfig.getCarbsOnBoardMigrationPercent()
            ),
            "glucoseData", Map.of(
                "enabled", featureToggleConfig.isGlucoseDataEnabled(),
                "migrationPercent", featureToggleConfig.getGlucoseDataMigrationPercent()
            ),
            "userConfiguration", Map.of(
                "enabled", featureToggleConfig.isUserConfigurationEnabled(),
                "migrationPercent", 0
            )
        );
    }
    
    @PostMapping("/toggle/{feature}")
    public Map<String, Object> toggleFeature(@PathVariable String feature, @RequestBody Map<String, Object> request) {
        // In a real application, this would require admin privileges
        // For now, just return the current status
        return Map.of(
            "feature", feature,
            "message", "Feature toggle updated (admin only in production)",
            "currentStatus", getFeatureStatus()
        );
    }
    
    @GetMapping("/check/{feature}")
    public Map<String, Object> checkFeature(@PathVariable String feature, @RequestParam(required = false) String userId) {
        boolean shouldUseBackend = featureToggleService.shouldUseBackend(feature);
        boolean shouldMigrate = userId != null ? featureToggleService.shouldMigrate(feature, userId) : false;
        
        return Map.of(
            "feature", feature,
            "shouldUseBackend", shouldUseBackend,
            "shouldMigrate", shouldMigrate,
            "migrationPercent", featureToggleService.getMigrationPercent(feature),
            "backendModeEnabled", featureToggleService.isBackendModeEnabled()
        );
    }
}
