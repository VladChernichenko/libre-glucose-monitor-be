package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.ConfirmUnloggedEventRequest;
import che.glucosemonitorbe.dto.UnloggedEventFlagDTO;
import che.glucosemonitorbe.entity.UnloggedEventFlag.State;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.UnloggedEventDetectionService;
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
 * Read + resolve API for unlogged-event flags — windows where glucose moved in a way the user's logged
 * inputs don't explain. Flags are produced by the periodic scanner; this endpoint lets the app show
 * them and let the user confirm (optionally backfilling the real amount) or dismiss.
 */
@Tag(name = "Unlogged events",
        description = "Detected probable unlogged/under-estimated food or insulin, for user confirmation")
@RestController
@RequestMapping("/api/unlogged-events")
@RequiredArgsConstructor
public class UnloggedEventController {

    private final UnloggedEventDetectionService detectionService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;

    @Operation(summary = "List the authenticated user's unlogged-event flags (optionally filtered by state)")
    @GetMapping
    public ResponseEntity<List<UnloggedEventFlagDTO>> list(Authentication auth,
                                                           @RequestParam(required = false) State state) {
        requireFeature();
        return ResponseEntity.ok(detectionService.list(userId(auth), state));
    }

    @Operation(summary = "Confirm a flag; optionally backfill the actual carbs/insulin as a real note")
    @PostMapping("/{id}/confirm")
    public ResponseEntity<UnloggedEventFlagDTO> confirm(Authentication auth, @PathVariable UUID id,
                                                        @RequestBody(required = false) ConfirmUnloggedEventRequest body) {
        requireFeature();
        Double carbs = body != null ? body.carbs() : null;
        Double insulin = body != null ? body.insulin() : null;
        return ResponseEntity.ok(detectionService.confirm(userId(auth), id, carbs, insulin));
    }

    @Operation(summary = "Dismiss a flag (the move was real model error, not an unlogged event)")
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<UnloggedEventFlagDTO> dismiss(Authentication auth, @PathVariable UUID id) {
        requireFeature();
        return ResponseEntity.ok(detectionService.dismiss(userId(auth), id));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void requireFeature() {
        if (!featureToggleService.isEnabled("unlogged-event-detection-enabled")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unlogged-event detection is not enabled");
        }
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
