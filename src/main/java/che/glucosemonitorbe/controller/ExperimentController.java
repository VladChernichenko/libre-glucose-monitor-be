package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.service.ExperimentService;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Tag(name = "Experiments", description = "Guided ISF and Carb Ratio determination experiments")
@RestController
@RequestMapping("/api/experiments")
@RequiredArgsConstructor
public class ExperimentController {

    private final ExperimentService experimentService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;

    @Operation(summary = "Check whether the metabolic background is clean enough to start an experiment")
    @GetMapping("/check-background")
    public ResponseEntity<BackgroundStatusDTO> checkBackground(
            Authentication auth,
            @RequestParam(required = false) String clientTimestamp) {
        requireFeature();
        return ResponseEntity.ok(experimentService.checkBackground(userId(auth), clientTimestamp));
    }

    @Operation(summary = "List experiments available to the user with lock/unlock status")
    @GetMapping("/available")
    public ResponseEntity<List<AvailableExperimentDTO>> getAvailable(Authentication auth) {
        requireFeature();
        return ResponseEntity.ok(experimentService.getAvailableExperiments(userId(auth)));
    }

    @Operation(summary = "Start a new experiment (background must be clean)")
    @PostMapping
    public ResponseEntity<ExperimentDTO> start(Authentication auth,
                                               @RequestBody StartExperimentRequest req,
                                               @RequestParam(required = false) String clientTimestamp) {
        requireFeature();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(experimentService.startExperiment(userId(auth), req, clientTimestamp));
    }

    @Operation(summary = "Get a single experiment with all its readings")
    @GetMapping("/{id}")
    public ResponseEntity<ExperimentDTO> get(Authentication auth, @PathVariable UUID id) {
        requireFeature();
        return ResponseEntity.ok(experimentService.getExperiment(id, userId(auth)));
    }

    @Operation(summary = "Record a glucose reading during an active experiment")
    @PostMapping("/{id}/reading")
    public ResponseEntity<ExperimentDTO> recordReading(Authentication auth,
                                                       @PathVariable UUID id,
                                                       @RequestBody RecordReadingRequest req) {
        requireFeature();
        return ResponseEntity.ok(experimentService.recordReading(id, userId(auth), req));
    }

    @Operation(summary = "Complete an experiment and compute / save the result")
    @PostMapping("/{id}/complete")
    public ResponseEntity<ExperimentResultDTO> complete(Authentication auth, @PathVariable UUID id,
                                                         @RequestParam(required = false) String clientTimestamp) {
        requireFeature();
        return ResponseEntity.ok(experimentService.completeExperiment(id, userId(auth), clientTimestamp));
    }

    @Operation(summary = "Abandon an in-progress experiment")
    @PostMapping("/{id}/abandon")
    public ResponseEntity<ExperimentDTO> abandon(Authentication auth, @PathVariable UUID id) {
        requireFeature();
        return ResponseEntity.ok(experimentService.abandonExperiment(id, userId(auth)));
    }

    @Operation(summary = "Get full experiment history for the authenticated user")
    @GetMapping
    public ResponseEntity<List<ExperimentDTO>> history(Authentication auth) {
        requireFeature();
        return ResponseEntity.ok(experimentService.getHistory(userId(auth)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void requireFeature() {
        if (!featureToggleService.isEnabled("experiments-enabled")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiments feature is not enabled");
        }
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
