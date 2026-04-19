package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.FeatureToggleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import jakarta.validation.Valid;
import java.util.Map;

@Tag(name = "Glucose Calculations", description = "Glucose trend, delta, and 2-hour prediction calculations")
@RestController
@RequestMapping("/api/glucose-calculations")
@RequiredArgsConstructor
public class GlucoseCalculationsController {
    
    private final GlucoseCalculationsService glucoseCalculationsService;
    private final FeatureToggleService featureToggleService;
    
    @Operation(summary = "Get glucose calculations (GET variant)")
    @ApiResponse(responseCode = "200", description = "Calculation result returned")
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
    
    @Operation(summary = "Calculate glucose data (POST variant with full request body)")
    @ApiResponse(responseCode = "200", description = "Calculation result returned")
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