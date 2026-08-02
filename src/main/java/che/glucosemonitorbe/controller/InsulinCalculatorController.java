package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.exception.CustomErrorResponse;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
    public ResponseEntity<?> calculateInsulin(@Valid @RequestBody InsulinCalculationRequest request,
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
        
        // No blanket catch: GlobalExceptionHandler owns both the 422 refusal path and the
        // 500 path, and it logs. Swallowing every exception into a fixed 400 left a
        // misbehaving dose calculation with no trace at all.
        InsulinCalculationResponse response = insulinCalculatorService.calculateRecommendedInsulin(request);
        return ResponseEntity.ok(Map.of(
            "data", response,
            "featureEnabled", true,
            "backendMode", true,
            "message", "Calculation completed using backend service"
        ));
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

    /**
     * Validation failures on a dose request are 422, not 400: the body parsed fine, but the
     * values are not safe to dose from. A currentGlucose DecimalMax violation specifically
     * means a probable mg/dL payload, which is worth telling the client apart.
     *
     * <p>Controller-local on purpose - GlobalExceptionHandler maps validation failures to
     * 400 for every other endpoint, and that stays true.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CustomErrorResponse> handleInvalidDoseRequest(
            MethodArgumentNotValidException ex, HttpServletRequest httpRequest) {
        boolean impossibleGlucose = ex.getBindingResult().getFieldErrors().stream()
                .anyMatch(e -> "currentGlucose".equals(e.getField()) && "DecimalMax".equals(e.getCode()));
        DosingRefusalReason reason = impossibleGlucose
                ? DosingRefusalReason.GLUCOSE_IMPLAUSIBLE_UNIT
                : DosingRefusalReason.INVALID_INPUT;
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new CustomErrorResponse(
                        HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        reason.name(),
                        reason.getMessage(),
                        httpRequest.getRequestURI()));
    }

    private UUID requireUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalArgumentException("Invalid authentication");
        }
        return userService.getUserByUsername(authentication.getName()).getId();
    }
}
