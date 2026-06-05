package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.IsfMealWindowProfileResponse;
import che.glucosemonitorbe.service.IsfMealWindowProfileService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Exposes the cached observational ISF profile (3 meal-window buckets).
 *
 * <ul>
 *   <li>{@code GET /api/isf/meal-windows} — cached snapshot read (cheap)</li>
 *   <li>{@code POST /api/isf/meal-windows/recompute} — force on-demand recomputation</li>
 * </ul>
 */
@Tag(name = "ISF Profile", description = "Observational per-meal-window Insulin Sensitivity Factor estimates")
@RestController
@RequestMapping("/api/isf/meal-windows")
@RequiredArgsConstructor
public class IsfMealWindowController {

    private final IsfMealWindowProfileService isfProfileService;
    private final UserService userService;

    @Operation(summary = "Get cached per-meal-window ISF profile (breakfast, lunch, dinner)")
    @GetMapping
    public ResponseEntity<IsfMealWindowProfileResponse> getProfile(Authentication auth) {
        return ResponseEntity.ok(isfProfileService.getProfile(userId(auth)));
    }

    @Operation(summary = "Force a recomputation of the meal-window ISF profile from the last 14 days of data")
    @PostMapping("/recompute")
    public ResponseEntity<IsfMealWindowProfileResponse> recompute(Authentication auth) {
        return ResponseEntity.ok(isfProfileService.recomputeForUser(userId(auth)));
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
