package che.glucosemonitorbe.sick;

import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.function.Function;

/** Sick mode: a user-declared illness episode with a severity. */
@Tag(name = "Sick Mode", description = "Declare, adjust, confirm and end an illness episode")
@RestController
@RequestMapping("/api/sick-mode")
@RequiredArgsConstructor
public class SickModeController {

    public record StartRequest(SickSeverity severity, Since since) {}

    public record SeverityRequest(SickSeverity severity) {}

    public record EndRequest(String reason) {}

    private final SickModeService sickModeService;
    private final UserService userService;

    @Operation(summary = "Current sick-mode status")
    @GetMapping
    public ResponseEntity<SickModeStatusDTO> status(Authentication authentication) {
        return ResponseEntity.ok(sickModeService.status(userId(authentication)));
    }

    @Operation(summary = "Start sick mode, optionally backdated")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Sick mode started"),
                    @ApiResponse(responseCode = "400", description = "Already active, unselectable severity or start too early") })
    @PostMapping("/start")
    public ResponseEntity<?> start(Authentication authentication, @RequestBody StartRequest request) {
        return run(authentication, id -> sickModeService.start(id, request.severity(), request.since()));
    }

    @Operation(summary = "Change the severity of the active episode")
    @PutMapping("/severity")
    public ResponseEntity<?> changeSeverity(Authentication authentication, @RequestBody SeverityRequest request) {
        return run(authentication, id -> sickModeService.changeSeverity(id, request.severity()));
    }

    @Operation(summary = "Confirm the user is still sick")
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(Authentication authentication) {
        return run(authentication, sickModeService::confirm);
    }

    @Operation(summary = "End sick mode (reason USER or MORNING_NO)")
    @PostMapping("/end")
    public ResponseEntity<?> end(Authentication authentication, @RequestBody EndRequest request) {
        return run(authentication, id -> sickModeService.end(id, request.reason()));
    }

    private ResponseEntity<?> run(Authentication authentication, Function<UUID, SickModeStatusDTO> command) {
        try {
            return ResponseEntity.ok(command.apply(userId(authentication)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private UUID userId(Authentication authentication) {
        return userService.getUserByUsername(authentication.getName()).getId();
    }
}
