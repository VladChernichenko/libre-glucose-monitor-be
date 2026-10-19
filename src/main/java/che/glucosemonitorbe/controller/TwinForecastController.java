package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.AnchorForecastDTO;
import che.glucosemonitorbe.dto.ForecastAnchorDTO;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.TwinForecastHistoryService;
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

/** "Forecast at each log": the empirical twin's forecast from each logged note (spec F2.3/F2.4). */
@Tag(name = "Empirical twin", description = "Forecast at each log, for browsing history")
@RestController
@RequestMapping("/api/twin")
@RequiredArgsConstructor
public class TwinForecastController {

    private final TwinForecastHistoryService historyService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;

    @Operation(summary = "Doses, meals and fast sugars logged in the last `days` days, oldest first")
    @GetMapping("/anchors")
    public ResponseEntity<List<ForecastAnchorDTO>> anchors(Authentication auth,
            @RequestParam(defaultValue = "14") int days) {
        requireFeature();
        return ResponseEntity.ok(historyService.anchors(userId(auth), days));
    }

    @Operation(summary = "The twin's 4 h forecast from one logged note, with the CGM actuals")
    @GetMapping("/anchors/{noteId}")
    public ResponseEntity<AnchorForecastDTO> forecast(Authentication auth, @PathVariable UUID noteId) {
        requireFeature();
        return ResponseEntity.ok(historyService.forecast(userId(auth), noteId));
    }

    private void requireFeature() {
        if (!featureToggleService.isEnabled("empirical-twin-enabled")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empirical twin is not enabled");
        }
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
