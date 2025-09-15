package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.service.COBSettingsService;
import che.glucosemonitorbe.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/cob-settings")
public class COBSettingsController {
    
    @Autowired
    private COBSettingsService cobSettingsService;
    
    @Autowired
    private UserService userService;
    
    /**
     * Get COB settings for the authenticated user
     * @param authentication the authentication object
     * @return COB settings DTO
     */
    @GetMapping
    public ResponseEntity<COBSettingsDTO> getCOBSettings(Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            COBSettingsDTO settings = cobSettingsService.getCOBSettings(userId);
            return ResponseEntity.ok(settings);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Create or update COB settings for the authenticated user
     * @param authentication the authentication object
     * @param settingsDTO the settings to save
     * @return the saved COB settings DTO
     */
    @PostMapping
    public ResponseEntity<COBSettingsDTO> saveCOBSettings(
            Authentication authentication,
            @RequestBody COBSettingsDTO settingsDTO) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            COBSettingsDTO savedSettings = cobSettingsService.saveCOBSettings(userId, settingsDTO);
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
    public ResponseEntity<COBSettingsDTO> updateCOBSettings(
            Authentication authentication,
            @RequestBody COBSettingsDTO settingsDTO) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            COBSettingsDTO updatedSettings = cobSettingsService.saveCOBSettings(userId, settingsDTO);
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
    public ResponseEntity<Void> deleteCOBSettings(Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            cobSettingsService.deleteCOBSettings(userId);
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
    public ResponseEntity<Boolean> hasCOBSettings(Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            boolean exists = cobSettingsService.hasCOBSettings(userId);
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
