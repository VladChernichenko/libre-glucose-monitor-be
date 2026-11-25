package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.AcceptSuggestionRequest;
import che.glucosemonitorbe.dto.IsfMealWindowProfileResponse;
import che.glucosemonitorbe.dto.IsfMealWindowSuggestionDTO;
import che.glucosemonitorbe.service.CarbRatioMealWindowProfileService;
import che.glucosemonitorbe.service.IsfMealWindowProfileService;
import che.glucosemonitorbe.service.IsfMealWindowSuggestionService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Exposes the cached observational ISF profile and the morning suggestion banner.
 */
@Tag(name = "ISF Profile", description = "Observational per-meal-window Insulin Sensitivity Factor estimates")
@RestController
@RequestMapping("/api/isf/meal-windows")
@RequiredArgsConstructor
public class IsfMealWindowController {

    private final IsfMealWindowProfileService isfProfileService;
    private final CarbRatioMealWindowProfileService carbRatioProfileService;
    private final IsfMealWindowSuggestionService suggestionService;
    private final UserService userService;

    @Operation(summary = "Get cached per-meal-window ISF profile (breakfast, lunch, dinner, night)")
    @GetMapping
    public ResponseEntity<IsfMealWindowProfileResponse> getProfile(Authentication auth) {
        return ResponseEntity.ok(isfProfileService.getProfile(userId(auth)));
    }

    @Operation(summary = "Force a recomputation of the meal-window ISF and carb-ratio profiles from the last 14 days of data; returns the ISF profile")
    @PostMapping("/recompute")
    public ResponseEntity<IsfMealWindowProfileResponse> recompute(Authentication auth) {
        UUID userId = userId(auth);
        IsfMealWindowProfileResponse isfProfile = isfProfileService.recomputeForUser(userId);
        carbRatioProfileService.recomputeForUser(userId);
        return ResponseEntity.ok(isfProfile);
    }

    @Operation(summary = "Morning ISF and carb-ratio suggestion banner state (show at most every 3 days)")
    @GetMapping("/suggestion")
    public ResponseEntity<IsfMealWindowSuggestionDTO> getSuggestion(Authentication auth) {
        return ResponseEntity.ok(suggestionService.getSuggestion(userId(auth)));
    }

    @Operation(summary = "Accept proposed meal-window ISF values into user settings; carb ratios only when includeRatios is true")
    @PostMapping("/suggestion/accept")
    public ResponseEntity<Void> acceptSuggestion(
            Authentication auth, @RequestBody(required = false) AcceptSuggestionRequest body) {
        boolean includeRatios = body != null && Boolean.TRUE.equals(body.includeRatios());
        try {
            suggestionService.accept(userId(auth), includeRatios);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @Operation(summary = "Dismiss the ISF and carb-ratio suggestion banner (suppresses for 3 days)")
    @PostMapping("/suggestion/dismiss")
    public ResponseEntity<Void> dismissSuggestion(Authentication auth) {
        suggestionService.dismiss(userId(auth));
        return ResponseEntity.ok().build();
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
