package che.glucosemonitorbe.watch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.ZoneId;

@Tag(name = "Apple Watch", description = "Compact glance for the watch app and its complication")
@RestController
@RequestMapping("/api/watch")
@RequiredArgsConstructor
public class WatchGlanceController {

    private final WatchGlanceService glanceService;

    @Operation(summary = "Newest reading, stale cause, 2 h prediction and the dashboard watch strip")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Glance returned"),
                    @ApiResponse(responseCode = "400", description = "tz missing or not an IANA time zone") })
    @GetMapping("/glance")
    public ResponseEntity<WatchGlance> glance(@RequestParam("tz") String tz, Authentication authentication) {
        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(glanceService.glance(authentication.getName(), zone));
    }
}
