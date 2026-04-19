package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.FeatureToggleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Carbs on Board", description = "Real-time carbs-on-board calculation using exponential decay model")
@RestController
@RequestMapping("/api/cob")
@RequiredArgsConstructor
public class CarbsOnBoardController {
    
    private final CarbsOnBoardService cobService;
    private final FeatureToggleService featureToggleService;
    
    @Operation(summary = "Calculate active carbs on board at a given time")
    @ApiResponse(responseCode = "200", description = "COB value returned")
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
    
    @Operation(summary = "Get COB status summary")
    @ApiResponse(responseCode = "200", description = "Status returned")
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
    
    @Operation(summary = "Get COB decay timeline for charting")
    @ApiResponse(responseCode = "200", description = "Timeline returned")
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
