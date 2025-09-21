package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.FeatureToggleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import jakarta.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/glucose-calculations")
@RequiredArgsConstructor
public class GlucoseCalculationsController {
    
    private final GlucoseCalculationsService glucoseCalculationsService;
    private final FeatureToggleService featureToggleService;
    
    /**
     * Get comprehensive glucose calculations for the current user
     */
    @GetMapping("/")
    public ResponseEntity<?> getGlucoseCalculations(
            @RequestParam Double currentGlucose,
            @RequestParam(required = false) Boolean includePredictionFactors,
            @RequestParam(required = false) Integer predictionHorizonMinutes,
            Authentication authentication) {
        
        String userId = authentication != null ? authentication.getName() : null;
        
        // Check if this feature should use the backend
        if (!featureToggleService.shouldUseBackend("glucose-calculations")) {
            return ResponseEntity.ok(Map.of(
                "message", "Feature not enabled - using frontend logic",
                "featureEnabled", false,
                "backendMode", false
            ));
        }
        
        // Check if this user should be migrated
        if (userId != null && !featureToggleService.shouldMigrate("glucose-calculations", userId)) {
            return ResponseEntity.ok(Map.of(
                "message", "User not in migration group - using frontend logic",
                "featureEnabled", true,
                "backendMode", false,
                "migrationPercent", featureToggleService.getMigrationPercent("glucose-calculations")
            ));
        }
        
        try {
            GlucoseCalculationsRequest request = GlucoseCalculationsRequest.builder()
                    .currentGlucose(currentGlucose)
                    .userId(userId)
                    .includePredictionFactors(includePredictionFactors != null ? includePredictionFactors : true)
                    .predictionHorizonMinutes(predictionHorizonMinutes)
                    .build();
            
            GlucoseCalculationsResponse response = glucoseCalculationsService.calculateGlucoseData(request);
            
            return ResponseEntity.ok(Map.of(
                "data", response,
                "featureEnabled", true,
                "backendMode", true,
                "message", "Glucose calculations completed using backend service"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Calculation failed",
                "message", e.getMessage(),
                "featureEnabled", true,
                "backendMode", true
            ));
        }
    }
    
    /**
     * Get glucose calculations using POST method for more complex requests
     */
    @PostMapping("/")
    public ResponseEntity<?> calculateGlucoseData(
            @Valid @RequestBody GlucoseCalculationsRequest request,
            Authentication authentication) {
        
        String userId = authentication != null ? authentication.getName() : null;
        request.setUserId(userId);
        
        // Check if this feature should use the backend
        if (!featureToggleService.shouldUseBackend("glucose-calculations")) {
            return ResponseEntity.ok(Map.of(
                "message", "Feature not enabled - using frontend logic",
                "featureEnabled", false,
                "backendMode", false
            ));
        }
        
        // Check if this user should be migrated
        if (userId != null && !featureToggleService.shouldMigrate("glucose-calculations", userId)) {
            return ResponseEntity.ok(Map.of(
                "message", "User not in migration group - using frontend logic",
                "featureEnabled", true,
                "backendMode", false,
                "migrationPercent", featureToggleService.getMigrationPercent("glucose-calculations")
            ));
        }
        
        try {
            GlucoseCalculationsResponse response = glucoseCalculationsService.calculateGlucoseData(request);
            
            return ResponseEntity.ok(Map.of(
                "data", response,
                "featureEnabled", true,
                "backendMode", true,
                "message", "Glucose calculations completed using backend service"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Calculation failed",
                "message", e.getMessage(),
                "featureEnabled", true,
                "backendMode", true
            ));
        }
    }
    
    /**
     * Get feature status for glucose calculations
     */
    @GetMapping("/status")
    public ResponseEntity<?> getFeatureStatus() {
        return ResponseEntity.ok(Map.of(
            "featureEnabled", featureToggleService.shouldUseBackend("glucose-calculations"),
            "migrationPercent", featureToggleService.getMigrationPercent("glucose-calculations"),
            "backendMode", featureToggleService.isBackendModeEnabled(),
            "message", "Glucose calculations feature status"
        ));
    }
}