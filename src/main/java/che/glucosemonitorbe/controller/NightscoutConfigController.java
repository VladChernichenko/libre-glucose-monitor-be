package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.NightscoutConfigRequestDto;
import che.glucosemonitorbe.dto.NightscoutConfigResponseDto;
import che.glucosemonitorbe.dto.NightscoutTestRequest;
import che.glucosemonitorbe.service.NightscoutConfigService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
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
    private final NightScoutIntegration nightScoutIntegration;
    
    /**
     * Create or update Nightscout configuration for the authenticated user
     */
    @PostMapping
    public ResponseEntity<NightscoutConfigResponseDto> saveConfig(
            @Valid @RequestBody NightscoutConfigRequestDto request,
            Authentication authentication) {
        
        log.info("User {} saving Nightscout configuration", authentication.getName());
        log.info("Request data - URL: {}, API Secret present: {}, API Token present: {}", 
                request.getNightscoutUrl(), 
                request.getApiSecret() != null && !request.getApiSecret().isEmpty(),
                request.getApiToken() != null && !request.getApiToken().isEmpty());
        
        try {
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            log.info("Retrieved user ID: {} for username: {}", userId, authentication.getName());
            
            NightscoutConfigResponseDto config = configService.saveConfig(userId, request);
            log.info("Successfully saved Nightscout configuration for user {} with ID: {}", authentication.getName(), config.getId());
            
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Failed to save Nightscout configuration for user {}: {}", authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
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
     * Test Nightscout configuration with provided credentials
     */
    @PostMapping("/test")
    public ResponseEntity<String> testConfig(@Valid @RequestBody NightscoutTestRequest request, Authentication authentication) {
        log.info("User {} testing Nightscout configuration with provided credentials", authentication.getName());
        
        try {
            // Validate request data
            if (request.getNightscoutUrl() == null || request.getNightscoutUrl().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Nightscout URL is required");
            }
            
            // Clean and validate URL
            String cleanUrl = request.getNightscoutUrl().trim().replaceAll("/$", "");
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                return ResponseEntity.badRequest().body("Nightscout URL must start with http:// or https://");
            }
            
            log.info("Testing connection to Nightscout: {}", cleanUrl);
            
            // Create a temporary config object for testing
            che.glucosemonitorbe.domain.NightscoutConfig testConfig = che.glucosemonitorbe.domain.NightscoutConfig.builder()
                    .nightscoutUrl(cleanUrl)
                    .apiSecret(request.getApiSecret())
                    .apiToken(request.getApiToken())
                    .isActive(true)
                    .build();
            
            // Test the connection by trying to fetch a small amount of data
            try {
                List<che.glucosemonitorbe.dto.NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntries(1, testConfig);
                log.info("Successfully connected to Nightscout for user {}. Found {} entries", authentication.getName(), entries.size());
                
                return ResponseEntity.ok("Connection test successful! Successfully connected to your Nightscout site.");
                
            } catch (Exception e) {
                log.error("Failed to connect to Nightscout for user {}: {}", authentication.getName(), e.getMessage());
                
                // Provide more specific error messages based on the exception
                String errorMessage = "Connection test failed: ";
                if (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized")) {
                    errorMessage += "Authentication failed. Please check your API secret and token.";
                } else if (e.getMessage().contains("404") || e.getMessage().contains("Not Found")) {
                    errorMessage += "Nightscout site not found. Please check your URL.";
                } else if (e.getMessage().contains("timeout") || e.getMessage().contains("Connection refused")) {
                    errorMessage += "Cannot reach your Nightscout site. Please check your URL and internet connection.";
                } else if (e.getMessage().contains("SSL") || e.getMessage().contains("certificate")) {
                    errorMessage += "SSL certificate error. Please check your Nightscout URL.";
                } else {
                    errorMessage += e.getMessage();
                }
                
                return ResponseEntity.badRequest().body(errorMessage);
            }
            
        } catch (Exception e) {
            log.error("Error during Nightscout configuration test for user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Configuration test failed: " + e.getMessage());
        }
    }
    
    /**
     * Test Nightscout configuration (GET version)
     */
    @GetMapping("/test")
    public ResponseEntity<String> testConfigGet(Authentication authentication) {
        // GET version requires stored configuration
        log.info("User {} testing stored Nightscout configuration", authentication.getName());
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        if (!configService.hasConfig(userId)) {
            return ResponseEntity.badRequest().body("No Nightscout configuration found. Please use POST method with credentials.");
        }
        
        try {
            // Get the user's active configuration
            Optional<che.glucosemonitorbe.domain.NightscoutConfig> configOpt = configService.getConfigForApiCalls(userId);
            if (configOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("No active Nightscout configuration found");
            }
            
            che.glucosemonitorbe.domain.NightscoutConfig config = configOpt.get();
            log.info("Testing connection to stored Nightscout: {}", config.getNightscoutUrl());
            
            // Test the connection by trying to fetch a small amount of data
            try {
                List<che.glucosemonitorbe.dto.NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntries(1, config);
                log.info("Successfully connected to stored Nightscout for user {}. Found {} entries", authentication.getName(), entries.size());
                
                // Mark as used after successful test
                configService.markAsUsed(userId);
                
                return ResponseEntity.ok("Connection test successful! Successfully connected to your stored Nightscout site.");
                
            } catch (Exception e) {
                log.error("Failed to connect to stored Nightscout for user {}: {}", authentication.getName(), e.getMessage());
                
                // Provide more specific error messages based on the exception
                String errorMessage = "Connection test failed: ";
                if (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized")) {
                    errorMessage += "Authentication failed. Please check your stored API secret and token.";
                } else if (e.getMessage().contains("404") || e.getMessage().contains("Not Found")) {
                    errorMessage += "Nightscout site not found. Please check your stored URL.";
                } else if (e.getMessage().contains("timeout") || e.getMessage().contains("Connection refused")) {
                    errorMessage += "Cannot reach your Nightscout site. Please check your stored URL and internet connection.";
                } else {
                    errorMessage += e.getMessage();
                }
                
                return ResponseEntity.badRequest().body(errorMessage);
            }
            
        } catch (Exception e) {
            log.error("Error during stored Nightscout configuration test for user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Configuration test failed: " + e.getMessage());
        }
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
    
    /**
     * Test database connection and basic operations
     */
    @GetMapping("/test-db")
    public ResponseEntity<Object> testDatabase(Authentication authentication) {
        log.info("Testing database connection for user: {}", authentication.getName());
        
        try {
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            log.info("Successfully retrieved user ID: {}", userId);
            
            boolean hasConfig = configService.hasConfig(userId);
            log.info("User has existing config: {}", hasConfig);
            
            long totalConfigs = configService.getActiveConfigCount();
            log.info("Total active configurations in database: {}", totalConfigs);
            
            return ResponseEntity.ok(new Object() {
                public final String status = "SUCCESS";
                public final String userIdStr = userId.toString();
                public final boolean userHasConfig = hasConfig;
                public final long totalConfigsCount = totalConfigs;
                public final String message = "Database connection and basic operations working correctly";
            });
        } catch (Exception e) {
            log.error("Database test failed for user {}: {}", authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new Object() {
                        public final String status = "ERROR";
                        public final String message = "Database test failed: " + e.getMessage();
                        public final String error = e.getClass().getSimpleName();
                    });
        }
    }
}
