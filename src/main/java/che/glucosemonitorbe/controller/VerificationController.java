package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.VerificationEventDTO;
import che.glucosemonitorbe.dto.VerificationSummaryDTO;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.VerificationService;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "Verification", description = "Real-life verification loop for ISF and Carb Ratio settings")
@RestController
@RequestMapping("/api/experiments/verification")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationService verificationService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;

    @Operation(summary = "Get the current rolling verification summary for the authenticated user")
    @GetMapping("/summary")
    public ResponseEntity<VerificationSummaryDTO> getSummary(Authentication auth) {
        requireFeature();
        return ResponseEntity.ok(verificationService.getSummary(userId(auth)));
    }

    @Operation(summary = "Accept the suggested setting refinement and save to UserSettings")
    @PostMapping("/accept-suggestion")
    public ResponseEntity<Void> acceptSuggestion(Authentication auth) {
        requireFeature();
        verificationService.acceptSuggestion(userId(auth));
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Get verification event history for the authenticated user")
    @GetMapping("/events")
    public ResponseEntity<List<VerificationEventDTO>> getEvents(Authentication auth) {
        requireFeature();
        return ResponseEntity.ok(verificationService.getEvents(userId(auth)));
    }

    // -- helpers ---------------------------------------------------------------

    private void requireFeature() {
        if (!featureToggleService.isEnabled("experiments-enabled")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiments feature is not enabled");
        }
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
