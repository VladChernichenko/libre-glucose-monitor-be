package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.dto.ActiveInsulinResponse;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.FeatureToggleService;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/insulin")
@RequiredArgsConstructor
public class InsulinCalculatorController {
    
    private final InsulinCalculatorService insulinCalculatorService;
    private final FeatureToggleService featureToggleService;
    
    @PostMapping("/calculate")
    public ResponseEntity<?> calculateInsulin(@RequestBody InsulinCalculationRequest request, 
                                           @RequestParam(required = false) String userId) {
        
        // Check if this feature should use the backend
        if (!featureToggleService.shouldUseBackend("insulin-calculator")) {
            return ResponseEntity.ok(Map.of(
                "message", "Feature not enabled - using frontend logic",
                "featureEnabled", false,
                "backendMode", false
            ));
        }
        
        // Check if this user should be migrated
        if (userId != null && !featureToggleService.shouldMigrate("insulin-calculator", userId)) {
            return ResponseEntity.ok(Map.of(
                "message", "User not in migration group - using frontend logic",
                "featureEnabled", true,
                "backendMode", false,
                "migrationPercent", featureToggleService.getMigrationPercent("insulin-calculator")
            ));
        }
        
        try {
            InsulinCalculationResponse response = insulinCalculatorService.calculateRecommendedInsulin(request);
            return ResponseEntity.ok(Map.of(
                "data", response,
                "featureEnabled", true,
                "backendMode", true,
                "message", "Calculation completed using backend service"
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
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam(required = false) String userId) {
        boolean shouldUseBackend = featureToggleService.shouldUseBackend("insulin-calculator");
        boolean shouldMigrate = userId != null ? featureToggleService.shouldMigrate("insulin-calculator", userId) : false;
        
        return ResponseEntity.ok(Map.of(
            "feature", "insulin-calculator",
            "shouldUseBackend", shouldUseBackend,
            "shouldMigrate", shouldMigrate,
            "migrationPercent", featureToggleService.getMigrationPercent("insulin-calculator"),
            "backendModeEnabled", featureToggleService.isBackendModeEnabled()
        ));
    }
    
    @PostMapping("/active-insulin")
    public ResponseEntity<?> getActiveInsulin(@RequestBody Map<String, Object> request,
                                           @RequestParam(required = false) String userId) {
        
        if (!featureToggleService.shouldUseBackend("insulin-calculator")) {
            return ResponseEntity.ok(Map.of(
                "message", "Feature not enabled - using frontend logic",
                "featureEnabled", false
            ));
        }
        
        // This would normally fetch insulin doses from the database
        // For now, returning a placeholder response
        return ResponseEntity.ok(Map.of(
            "message", "Active insulin calculation endpoint ready",
            "featureEnabled", true,
            "backendMode", true,
            "note", "Database integration pending"
        ));
    }
}
