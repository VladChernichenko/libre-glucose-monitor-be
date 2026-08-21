package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.ConfirmHypoEventRequest;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.HypoEventService;
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

/**
 * Read + resolve API for hypo events - windows where CGM glucose dropped below the hypo threshold.
 * Events are opened by the observer; this endpoint lets the app prompt the user to log a
 * fast-acting rescue carb, or dismiss the prompt.
 */
@Tag(name = "Hypo events",
        description = "Detected hypoglycaemia windows prompting a fast-carb rescue log")
@RestController
@RequestMapping("/api/hypo-events")
@RequiredArgsConstructor
public class HypoEventController {

    private final HypoEventService hypoEventService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;

    @Operation(summary = "List the authenticated user's hypo events (optionally filtered by state)")
    @GetMapping
    public ResponseEntity<List<HypoEventDTO>> list(Authentication auth,
                                                   @RequestParam(required = false) State state) {
        requireFeature();
        return ResponseEntity.ok(hypoEventService.list(userId(auth), state));
    }

    @Operation(summary = "Confirm a hypo event by logging the rescue carbs taken")
    @PostMapping("/{id}/confirm")
    public ResponseEntity<HypoEventDTO> confirm(Authentication auth, @PathVariable UUID id,
                                                @RequestBody ConfirmHypoEventRequest body) {
        requireFeature();
        Double grams = body != null ? body.grams() : null;
        return ResponseEntity.ok(hypoEventService.confirm(userId(auth), id, grams));
    }

    @Operation(summary = "Dismiss a hypo event without logging a rescue carb")
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<HypoEventDTO> dismiss(Authentication auth, @PathVariable UUID id) {
        requireFeature();
        return ResponseEntity.ok(hypoEventService.dismiss(userId(auth), id));
    }

    // -- helpers ---------------------------------------------------------------

    private void requireFeature() {
        if (!featureToggleService.isEnabled("hypo-rescue-logging-enabled")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Hypo rescue logging is not enabled");
        }
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
