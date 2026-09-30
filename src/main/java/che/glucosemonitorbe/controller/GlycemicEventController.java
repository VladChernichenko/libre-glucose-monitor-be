package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.ConfirmGlycemicEventRequest;
import che.glucosemonitorbe.dto.GlycemicEventDTO;
import che.glucosemonitorbe.dto.GlycemicEventHistoryDTO;
import che.glucosemonitorbe.dto.GlycemicInsightsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.GlycemicEventService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Glycemic events for the chart, the one-tap prompt, and the history view (spec section 5). */
@Tag(name = "Glycemic events", description = "Detected unexplained glucose moves, for user confirmation")
@RestController
@RequestMapping("/api/glycemic-events")
@RequiredArgsConstructor
public class GlycemicEventController {

    private final GlycemicEventService eventService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;

    @Operation(summary = "Events intersecting [from, to] (local wall time, at most 31 days)")
    @GetMapping
    public ResponseEntity<List<GlycemicEventDTO>> list(Authentication auth,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) State state) {
        requireFeature();
        return ResponseEntity.ok(eventService.listRange(userId(auth), from, to, state));
    }

    @Operation(summary = "The one event to prompt about now, or 204")
    @GetMapping("/prompt")
    public ResponseEntity<GlycemicEventDTO> prompt(Authentication auth) {
        requireFeature();
        return eventService.prompt(userId(auth)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Per-category counts, hour histograms, lipodystrophy recurrence and agreement rate")
    @GetMapping("/insights")
    public ResponseEntity<GlycemicInsightsDTO> insights(Authentication auth, @RequestParam(defaultValue = "30") int days) {
        requireFeature();
        return ResponseEntity.ok(eventService.insights(userId(auth), days));
    }

    @Operation(summary = "Confirm or relabel; optionally backfill the real carbs/insulin as a note")
    @PostMapping("/{id}/confirm")
    public ResponseEntity<GlycemicEventDTO> confirm(Authentication auth, @PathVariable UUID id,
            @RequestBody(required = false) ConfirmGlycemicEventRequest body) {
        requireFeature();
        return ResponseEntity.ok(eventService.confirm(userId(auth), id, body));
    }

    @Operation(summary = "Dismiss (this wasn't anything)")
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<GlycemicEventDTO> dismiss(Authentication auth, @PathVariable UUID id) {
        requireFeature();
        return ResponseEntity.ok(eventService.dismiss(userId(auth), id));
    }

    @Operation(summary = "The event's timeline, oldest first")
    @GetMapping("/{id}/history")
    public ResponseEntity<List<GlycemicEventHistoryDTO>> history(Authentication auth, @PathVariable UUID id) {
        requireFeature();
        return ResponseEntity.ok(eventService.history(userId(auth), id));
    }

    private void requireFeature() {
        if (!featureToggleService.isEnabled("glycemic-event-detection-enabled")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Glycemic event detection is not enabled");
        }
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
