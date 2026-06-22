package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.UserSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "COB Settings", description = "Carbs-on-board decay configuration per user")
@RestController
@RequestMapping("/api/user-settings")
@RequiredArgsConstructor
public class UserSettingsController {

    private final UserSettingsService userSettingsService;
    private final UserService userService;
    
    @Operation(summary = "Get COB settings for the authenticated user")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Settings returned"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized") })
    @GetMapping
    public ResponseEntity<UserSettingsDTO> getUserSettings(Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
            return ResponseEntity.ok(settings);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    @Operation(summary = "Create or update COB settings")
    @ApiResponse(responseCode = "200", description = "Settings saved")
    @PostMapping
    public ResponseEntity<UserSettingsDTO> saveUserSettings(
            Authentication authentication,
            @RequestBody UserSettingsDTO settingsDTO) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            UserSettingsDTO savedSettings = userSettingsService.saveUserSettings(userId, settingsDTO);
            return ResponseEntity.ok(savedSettings);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Update COB settings for the authenticated user
     * @param authentication the authentication object
     * @param settingsDTO the settings to update
     * @return the updated COB settings DTO
     */
    @PutMapping
    public ResponseEntity<UserSettingsDTO> updateUserSettings(
            Authentication authentication,
            @RequestBody UserSettingsDTO settingsDTO) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            UserSettingsDTO updatedSettings = userSettingsService.saveUserSettings(userId, settingsDTO);
            return ResponseEntity.ok(updatedSettings);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Delete COB settings for the authenticated user
     * @param authentication the authentication object
     * @return success response
     */
    @DeleteMapping
    public ResponseEntity<Void> deleteUserSettings(Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            userSettingsService.deleteUserSettings(userId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Check if COB settings exist for the authenticated user
     * @param authentication the authentication object
     * @return true if settings exist, false otherwise
     */
    @GetMapping("/exists")
    public ResponseEntity<Boolean> hasUserSettings(Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            boolean exists = userSettingsService.hasUserSettings(userId);
            return ResponseEntity.ok(exists);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Extract user ID from authentication object
     * @param authentication the authentication object
     * @return the user ID
     */
    private UUID getUserIdFromAuthentication(Authentication authentication) {
        if (authentication != null && authentication.getName() != null) {
            String username = authentication.getName();
            return userService.getUserByUsername(username).getId();
        }
        throw new IllegalArgumentException("Invalid authentication");
    }
}
