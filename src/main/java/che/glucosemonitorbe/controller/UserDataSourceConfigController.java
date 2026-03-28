package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.dto.DataSourceConfigRequestDto;
import che.glucosemonitorbe.dto.DataSourceConfigStatusDto;
import che.glucosemonitorbe.dto.NightscoutTestRequestDto;
import che.glucosemonitorbe.dto.NightscoutTestResponseDto;
import che.glucosemonitorbe.dto.UserDataSourceConfigDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.service.UserDataSourceConfigService;
import che.glucosemonitorbe.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/user/data-source-config")
@RequiredArgsConstructor
public class UserDataSourceConfigController {

    private final UserDataSourceConfigService configService;
    private final UserService userService;
    private final NightScoutIntegration nightScoutIntegration;

    /**
     * Save or update a data source configuration
     */
    @PostMapping
    public ResponseEntity<UserDataSourceConfigDto> saveConfig(
            @Valid @RequestBody DataSourceConfigRequestDto request,
            Authentication authentication) {
        
        try {
            log.info("User {} saving data source configuration: {}", authentication.getName(), request.getDataSource());
            
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            UserDataSourceConfigDto savedConfig = configService.saveConfig(userId, request);
            
            log.info("Successfully saved data source configuration for user {}", authentication.getName());
            return ResponseEntity.ok(savedConfig);
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid configuration request from user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error saving configuration for user {}: {}", authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get all configurations for the authenticated user
     */
    @GetMapping
    public ResponseEntity<List<UserDataSourceConfigDto>> getAllConfigs(Authentication authentication) {
        try {
            log.info("User {} requesting all data source configurations", authentication.getName());
            
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            List<UserDataSourceConfigDto> configs = configService.getAllConfigs(userId);
            
            log.info("Retrieved {} configurations for user {}", configs.size(), authentication.getName());
            return ResponseEntity.ok(configs);
            
        } catch (Exception e) {
            log.error("Error retrieving configurations for user {}: {}", authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get active configuration for a specific data source type
     */
    @GetMapping("/active/{dataSource}")
    public ResponseEntity<UserDataSourceConfigDto> getActiveConfig(
            @PathVariable UserDataSourceConfig.DataSourceType dataSource,
            Authentication authentication) {
        
        try {
            log.info("User {} requesting active {} configuration", authentication.getName(), dataSource);
            
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            return configService.getActiveConfig(userId, dataSource)
                    .map(config -> {
                        log.info("Found active {} configuration for user {}", dataSource, authentication.getName());
                        return ResponseEntity.ok(config);
                    })
                    .orElseGet(() -> {
                        log.info("No active {} configuration found for user {}", dataSource, authentication.getName());
                        return ResponseEntity.notFound().build();
                    });
            
        } catch (Exception e) {
            log.error("Error retrieving active {} configuration for user {}: {}", dataSource, authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get configuration status for the authenticated user
     */
    @GetMapping("/status")
    public ResponseEntity<DataSourceConfigStatusDto> getConfigStatus(Authentication authentication) {
        try {
            log.info("User {} requesting configuration status", authentication.getName());
            
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            DataSourceConfigStatusDto status = configService.getConfigStatus(userId);
            
            log.info("Retrieved configuration status for user {}: hasAnyConfig={}, preferredDataSource={}", 
                    authentication.getName(), status.isHasAnyConfig(), status.getPreferredDataSource());
            return ResponseEntity.ok(status);
            
        } catch (Exception e) {
            log.error("Error retrieving configuration status for user {}: {}", authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Activate a specific configuration
     */
    @PostMapping("/{configId}/activate")
    public ResponseEntity<UserDataSourceConfigDto> activateConfig(
            @PathVariable UUID configId,
            Authentication authentication) {
        
        try {
            log.info("User {} activating configuration {}", authentication.getName(), configId);
            
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            UserDataSourceConfigDto activatedConfig = configService.activateConfig(userId, configId);
            
            log.info("Successfully activated configuration {} for user {}", configId, authentication.getName());
            return ResponseEntity.ok(activatedConfig);
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid activation request from user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error activating configuration {} for user {}: {}", configId, authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Deactivate a specific configuration
     */
    @PostMapping("/{configId}/deactivate")
    public ResponseEntity<String> deactivateConfig(
            @PathVariable UUID configId,
            Authentication authentication) {
        
        try {
            log.info("User {} deactivating configuration {}", authentication.getName(), configId);
            
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            configService.deactivateConfig(userId, configId);
            
            log.info("Successfully deactivated configuration {} for user {}", configId, authentication.getName());
            return ResponseEntity.ok("Configuration deactivated successfully");
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid deactivation request from user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error deactivating configuration {} for user {}: {}", configId, authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete a configuration
     */
    @DeleteMapping("/{configId}")
    public ResponseEntity<String> deleteConfig(
            @PathVariable UUID configId,
            Authentication authentication) {
        
        try {
            log.info("User {} deleting configuration {}", authentication.getName(), configId);
            
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            configService.deleteConfig(userId, configId);
            
            log.info("Successfully deleted configuration {} for user {}", configId, authentication.getName());
            return ResponseEntity.ok("Configuration deleted successfully");
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid deletion request from user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error deleting configuration {} for user {}: {}", configId, authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Test Nightscout connectivity using the URL and credentials in the request body (does not save).
     */
    @PostMapping("/test-nightscout")
    public ResponseEntity<NightscoutTestResponseDto> testNightscout(
            @Valid @RequestBody NightscoutTestRequestDto request,
            Authentication authentication) {

        log.info("User {} running Nightscout connection test", authentication.getName());
        NightscoutTestResponseDto result = nightScoutIntegration.probeNightscout(
                request.getNightscoutUrl(),
                request.getNightscoutApiSecret(),
                request.getNightscoutApiToken());
        return ResponseEntity.ok(result);
    }

    /**
     * Test a configuration
     */
    @PostMapping("/{configId}/test")
    public ResponseEntity<String> testConfig(
            @PathVariable UUID configId,
            Authentication authentication) {
        
        try {
            log.info("User {} testing configuration {}", authentication.getName(), configId);
            
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            boolean isValid = configService.testConfig(userId, configId);
            
            if (isValid) {
                log.info("Configuration {} test passed for user {}", configId, authentication.getName());
                return ResponseEntity.ok("Configuration test successful");
            } else {
                log.warn("Configuration {} test failed for user {}", configId, authentication.getName());
                return ResponseEntity.badRequest().body("Configuration test failed");
            }
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid test request from user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error testing configuration {} for user {}: {}", configId, authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Update last used timestamp for a configuration
     */
    @PostMapping("/{configId}/last-used")
    public ResponseEntity<String> updateLastUsed(
            @PathVariable UUID configId,
            Authentication authentication) {
        
        try {
            log.debug("User {} updating last used timestamp for configuration {}", authentication.getName(), configId);
            
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            configService.updateLastUsed(userId, configId);
            
            return ResponseEntity.ok("Last used timestamp updated");
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid last used update request from user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error updating last used timestamp for configuration {} for user {}: {}", configId, authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

