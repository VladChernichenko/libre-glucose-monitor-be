package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.AnchorForecastDTO;
import che.glucosemonitorbe.dto.ForecastAnchorDTO;
import che.glucosemonitorbe.service.TwinForecastHistoryService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** "Forecast at each log": the empirical twin's forecast from each logged note (spec F2.3/F2.4). */
@Tag(name = "Empirical twin", description = "Forecast at each log, for browsing history")
@RestController
@RequestMapping("/api/twin")
@RequiredArgsConstructor
public class TwinForecastController {

    private final TwinForecastHistoryService historyService;
    private final UserService userService;

    @Operation(summary = "Logged notes with a frozen forecast in the last `days` days, oldest first")
    @GetMapping("/anchors")
    public ResponseEntity<List<ForecastAnchorDTO>> anchors(Authentication auth,
            @RequestParam(defaultValue = "14") int days) {
        return ResponseEntity.ok(historyService.anchors(userId(auth), days));
    }

    @Operation(summary = "The 4 h forecast frozen when one note was logged, with the CGM actuals")
    @GetMapping("/anchors/{noteId}")
    public ResponseEntity<AnchorForecastDTO> forecast(Authentication auth, @PathVariable UUID noteId) {
        return ResponseEntity.ok(historyService.forecast(userId(auth), noteId));
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
