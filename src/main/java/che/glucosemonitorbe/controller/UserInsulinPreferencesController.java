package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.UpdateUserInsulinPreferencesRequest;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Insulin Preferences", description = "Per-user insulin preferences — ISF, carb ratio, selected insulin type")
@RestController
@RequestMapping("/api/user/insulin-preferences")
@RequiredArgsConstructor
public class UserInsulinPreferencesController {

    private final UserInsulinPreferencesService userInsulinPreferencesService;
    private final UserService userService;

    @Operation(summary = "Get insulin preferences for the authenticated user")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Preferences returned"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized") })
    @GetMapping
    public ResponseEntity<UserInsulinPreferencesDTO> get(Authentication authentication) {
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(userInsulinPreferencesService.getPreferences(userId));
    }

    @Operation(summary = "Update insulin preferences for the authenticated user")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Preferences updated"),
                    @ApiResponse(responseCode = "400", description = "Invalid values") })
    @PutMapping
    public ResponseEntity<?> put(
            Authentication authentication,
            @Valid @RequestBody UpdateUserInsulinPreferencesRequest request) {
        try {
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            UserInsulinPreferencesDTO saved = userInsulinPreferencesService.savePreferences(userId, request);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
