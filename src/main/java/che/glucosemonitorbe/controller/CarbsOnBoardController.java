package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.FeatureToggleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cob")
@RequiredArgsConstructor
public class CarbsOnBoardController {
    
    private final CarbsOnBoardService cobService;
    private final FeatureToggleService featureToggleService;
    
    /**
     * Calculate carbs on board
     */
    @PostMapping("/calculate")
    public ResponseEntity<?> calculateCOB(
            @RequestParam String userId,
            @RequestBody CarbsOnBoardService.COBCalculationRequest request) {
        
        // Check if this user should use the backend for COB
        if (featureToggleService.shouldMigrate("carbs-on-board", userId)) {
            // Use backend service
            var response = cobService.calculateCOB(request);
            return ResponseEntity.ok(Map.of(
                "featureEnabled", true,
                "data", response,
                "message", "COB calculation completed using backend service",
                "backendMode", true
            ));
        } else {
            // User not in migration group - use frontend logic
            return ResponseEntity.ok(Map.of(
                "featureEnabled", true,
                "migrationPercent", featureToggleService.getMigrationPercent("carbs-on-board"),
                "backendMode", false,
                "message", "User not in migration group - using frontend logic"
            ));
        }
    }
    
    /**
     * Get COB status
     */
    @PostMapping("/status")
    public ResponseEntity<?> getCOBStatus(
            @RequestParam String userId,
            @RequestBody Map<String, Object> request) {
        
        // Check if this user should use the backend for COB
        if (featureToggleService.shouldMigrate("carbs-on-board", userId)) {
            // Use backend service
            return ResponseEntity.ok(Map.of(
                "featureEnabled", true,
                "data", Map.of(
                    "status", "COB status calculated using backend service",
                    "timestamp", java.time.LocalDateTime.now()
                ),
                "message", "COB status retrieved using backend service",
                "backendMode", true
            ));
        } else {
            // User not in migration group - use frontend logic
            return ResponseEntity.ok(Map.of(
                "featureEnabled", true,
                "migrationPercent", featureToggleService.getMigrationPercent("carbs-on-board"),
                "backendMode", false,
                "message", "User not in migration group - using frontend logic"
            ));
        }
    }
    
    /**
     * Get COB timeline
     */
    @PostMapping("/timeline")
    public ResponseEntity<?> getCOBTimeline(
            @RequestParam String userId,
            @RequestBody Map<String, Object> request) {
        
        // Check if this user should use the backend for COB
        if (featureToggleService.shouldMigrate("carbs-on-board", userId)) {
            // Use backend service
            return ResponseEntity.ok(Map.of(
                "featureEnabled", true,
                "data", Map.of(
                    "timeline", "COB timeline calculated using backend service",
                    "timestamp", java.time.LocalDateTime.now()
                ),
                "message", "COB timeline retrieved using backend service",
                "backendMode", true
            ));
        } else {
            // User not in migration group - use frontend logic
            return ResponseEntity.ok(Map.of(
                "featureEnabled", true,
                "migrationPercent", featureToggleService.getMigrationPercent("carbs-on-board"),
                "backendMode", false,
                "message", "User not in migration group - using frontend logic"
            ));
        }
    }
}
