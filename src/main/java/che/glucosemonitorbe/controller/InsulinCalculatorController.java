package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Insulin Calculator", description = "Warsaw Method bolus calculation - standard + extended bolus from carbs, fat, protein")
@RestController
@RequestMapping("/api/insulin")
@RequiredArgsConstructor
public class InsulinCalculatorController {
    
    private final InsulinCalculatorService insulinCalculatorService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;
    
    @Operation(summary = "Calculate recommended insulin dose using Warsaw Method")
    @ApiResponse(responseCode = "200", description = "Insulin calculation result returned")
    @PostMapping("/calculate")
    public ResponseEntity<?> calculateInsulin(@RequestBody InsulinCalculationRequest request,
                                              Authentication authentication) {
        UUID authenticatedUserId = requireUserId(authentication);
        // Never trust client-supplied userId for ISF / migration - always bind to the JWT subject.
        request.setUserId(authenticatedUserId.toString());
        String migrationKey = authenticatedUserId.toString();
        
        if (!featureToggleService.shouldUseBackend("insulin-calculator")) {
            return ResponseEntity.ok(Map.of(
                "message", "Feature not enabled - using frontend logic",
                "featureEnabled", false,
                "backendMode", false
            ));
        }
        
        if (!featureToggleService.shouldMigrate("insulin-calculator", migrationKey)) {
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
                "message", "Unable to calculate insulin dose",
                "featureEnabled", true,
                "backendMode", true
            ));
        }
    }
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(Authentication authentication) {
        UUID authenticatedUserId = requireUserId(authentication);
        boolean shouldUseBackend = featureToggleService.shouldUseBackend("insulin-calculator");
        boolean shouldMigrate = featureToggleService.shouldMigrate(
                "insulin-calculator", authenticatedUserId.toString());
        
        return ResponseEntity.ok(Map.of(
            "feature", "insulin-calculator",
            "shouldUseBackend", shouldUseBackend,
            "shouldMigrate", shouldMigrate,
            "migrationPercent", featureToggleService.getMigrationPercent("insulin-calculator"),
            "backendModeEnabled", featureToggleService.isBackendModeEnabled()
        ));
    }
    
    @Operation(summary = "Get active insulin on board (IOB) for a user")
    @ApiResponse(responseCode = "200", description = "IOB value returned")
    @PostMapping("/active-insulin")
    public ResponseEntity<?> getActiveInsulin(@RequestBody Map<String, Object> request,
                                              Authentication authentication) {
        requireUserId(authentication);
        
        if (!featureToggleService.shouldUseBackend("insulin-calculator")) {
            return ResponseEntity.ok(Map.of(
                "message", "Feature not enabled - using frontend logic",
                "featureEnabled", false
            ));
        }
        
        return ResponseEntity.ok(Map.of(
            "message", "Active insulin calculation endpoint ready",
            "featureEnabled", true,
            "backendMode", true,
            "note", "Database integration pending"
        ));
    }

    private UUID requireUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalArgumentException("Invalid authentication");
        }
        return userService.getUserByUsername(authentication.getName()).getId();
    }
}
