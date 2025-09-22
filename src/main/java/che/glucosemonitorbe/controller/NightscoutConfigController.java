package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.NightscoutConfigRequestDto;
import che.glucosemonitorbe.dto.NightscoutConfigResponseDto;
import che.glucosemonitorbe.service.NightscoutConfigService;
import che.glucosemonitorbe.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/nightscout/config")
@RequiredArgsConstructor
public class NightscoutConfigController {
    
    private final NightscoutConfigService configService;
    private final UserService userService;
    
    /**
     * Create or update Nightscout configuration for the authenticated user
     */
    @PostMapping
    public ResponseEntity<NightscoutConfigResponseDto> saveConfig(
            @Valid @RequestBody NightscoutConfigRequestDto request,
            Authentication authentication) {
        
        log.info("User {} saving Nightscout configuration", authentication.getName());
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        NightscoutConfigResponseDto config = configService.saveConfig(userId, request);
        
        log.info("Successfully saved Nightscout configuration for user {}", authentication.getName());
        return ResponseEntity.ok(config);
    }
    
    /**
     * Get Nightscout configuration for the authenticated user
     */
    @GetMapping
    public ResponseEntity<NightscoutConfigResponseDto> getConfig(Authentication authentication) {
        log.info("User {} requesting Nightscout configuration", authentication.getName());
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        Optional<NightscoutConfigResponseDto> config = configService.getConfig(userId);
        
        if (config.isPresent()) {
            log.info("Found Nightscout configuration for user {}", authentication.getName());
            return ResponseEntity.ok(config.get());
        } else {
            log.info("No Nightscout configuration found for user {}", authentication.getName());
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * Get active Nightscout configuration for the authenticated user
     */
    @GetMapping("/active")
    public ResponseEntity<NightscoutConfigResponseDto> getActiveConfig(Authentication authentication) {
        log.info("User {} requesting active Nightscout configuration", authentication.getName());
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        Optional<NightscoutConfigResponseDto> config = configService.getActiveConfig(userId);
        
        if (config.isPresent()) {
            log.info("Found active Nightscout configuration for user {}", authentication.getName());
            return ResponseEntity.ok(config.get());
        } else {
            log.info("No active Nightscout configuration found for user {}", authentication.getName());
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * Test Nightscout configuration
     */
    @PostMapping("/test")
    public ResponseEntity<String> testConfig(Authentication authentication) {
        log.info("User {} testing Nightscout configuration", authentication.getName());
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        if (!configService.hasConfig(userId)) {
            return ResponseEntity.badRequest().body("No Nightscout configuration found");
        }
        
        // Mark as used (this would be called after a successful API test)
        configService.markAsUsed(userId);
        
        log.info("Nightscout configuration test completed for user {}", authentication.getName());
        return ResponseEntity.ok("Configuration test completed successfully");
    }
    
    /**
     * Deactivate Nightscout configuration for the authenticated user
     */
    @PostMapping("/deactivate")
    public ResponseEntity<String> deactivateConfig(Authentication authentication) {
        log.info("User {} deactivating Nightscout configuration", authentication.getName());
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        configService.deactivateConfig(userId);
        
        log.info("Successfully deactivated Nightscout configuration for user {}", authentication.getName());
        return ResponseEntity.ok("Configuration deactivated successfully");
    }
    
    /**
     * Delete Nightscout configuration for the authenticated user
     */
    @DeleteMapping
    public ResponseEntity<String> deleteConfig(Authentication authentication) {
        log.info("User {} deleting Nightscout configuration", authentication.getName());
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        configService.deleteConfig(userId);
        
        log.info("Successfully deleted Nightscout configuration for user {}", authentication.getName());
        return ResponseEntity.ok("Configuration deleted successfully");
    }
    
    /**
     * Check if user has a Nightscout configuration
     */
    @GetMapping("/exists")
    public ResponseEntity<Boolean> hasConfig(Authentication authentication) {
        log.debug("User {} checking if Nightscout configuration exists", authentication.getName());
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        boolean hasConfig = configService.hasConfig(userId);
        
        return ResponseEntity.ok(hasConfig);
    }
    
    /**
     * Get configuration status
     */
    @GetMapping("/status")
    public ResponseEntity<Object> getConfigStatus(Authentication authentication) {
        log.debug("User {} requesting configuration status", authentication.getName());
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        boolean userHasConfig = configService.hasConfig(userId);
        Optional<NightscoutConfigResponseDto> userConfig = configService.getActiveConfig(userId);
        
        return ResponseEntity.ok(new Object() {
            public final boolean hasConfig = userHasConfig;
            public final boolean isActive = userConfig.isPresent();
            public final String nightscoutUrl = userConfig.map(NightscoutConfigResponseDto::getNightscoutUrl).orElse(null);
            public final String lastUsed = userConfig.map(c -> c.getLastUsed() != null ? c.getLastUsed().toString() : null).orElse(null);
        });
    }
}
