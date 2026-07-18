package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.DigitalTwinStatusDTO;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.service.DigitalTwinCalibrationService;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Read + manual-trigger API for the per-user digital twin (the ML calibration of the prediction
 * model). The twin is normally refreshed nightly; this endpoint lets the app show its status and
 * kick an on-demand recalibration.
 */
@Tag(name = "Digital Twin", description = "Per-user ML calibration of glucose predictions from predicted-vs-actual error")
@RestController
@RequestMapping("/api/digital-twin")
@RequiredArgsConstructor
public class DigitalTwinController {

    private final DigitalTwinCalibrationService calibrationService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;

    @Operation(summary = "Get the current digital-twin status and accuracy diagnostics for the authenticated user")
    @GetMapping
    public ResponseEntity<DigitalTwinStatusDTO> getStatus(Authentication auth) {
        requireFeature();
        return ResponseEntity.ok(calibrationService.getStatus(userId(auth)));
    }

    @Operation(summary = "Recalibrate the authenticated user's digital twin now from their CGM history")
    @PostMapping("/recalibrate")
    public ResponseEntity<DigitalTwinStatusDTO> recalibrate(Authentication auth) {
        requireFeature();
        UUID userId = userId(auth);
        DigitalTwinCalibrator.Result result = calibrationService.calibrateUser(userId);
        if (result == null) {
            // Feature enabled but not enough data to attempt a fit.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not enough CGM history to calibrate a digital twin yet");
        }
        return ResponseEntity.ok(calibrationService.getStatus(userId));
    }

    // -- helpers ---------------------------------------------------------------

    private void requireFeature() {
        if (!featureToggleService.isEnabled("digital-twin-enabled")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Digital twin feature is not enabled");
        }
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
