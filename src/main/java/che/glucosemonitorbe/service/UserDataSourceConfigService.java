package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.CacheConfig;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.DataSourceConfigRequestDto;
import che.glucosemonitorbe.dto.DataSourceConfigStatusDto;
import che.glucosemonitorbe.dto.NightscoutCredentials;
import che.glucosemonitorbe.dto.UserDataSourceConfigDto;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserDataSourceConfigService {

    private final UserDataSourceConfigRepository repository;
    private final UserService userService;

    /**
     * Save or update a data source configuration for a user
     */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_NIGHTSCOUT_CREDENTIALS, key = "#userId")
    public UserDataSourceConfigDto saveConfig(UUID userId, DataSourceConfigRequestDto request) {
        log.info("Saving data source configuration for user {}: {}", userId, request.getDataSource());
        
        // Validate the request
        validateConfigRequest(request);
        
        // Get user
        User user = userService.getUserById(userId);
        
        // Deactivate existing configs of the same type
        repository.deactivateConfigsByUserIdAndDataSource(userId, request.getDataSource());
        
        // Create new configuration
        UserDataSourceConfig config;
        if (request.isNightscoutConfig()) {
            config = new UserDataSourceConfig(
                user,
                request.getNightscoutUrl(),
                request.getNightscoutApiSecret(),
                request.getNightscoutApiToken()
            );
        } else if (request.isLibreConfig()) {
            config = new UserDataSourceConfig(
                user,
                request.getLibreEmail(),
                request.getLibrePassword(),
                request.getLibrePatientId(),
                true // isLibreConfig flag
            );
        } else {
            throw new IllegalArgumentException("Invalid data source type: " + request.getDataSource());
        }
        
        config.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        
        UserDataSourceConfig savedConfig = repository.save(config);
        log.info("Successfully saved data source configuration with ID: {}", savedConfig.getId());
        
        return convertToDto(savedConfig);
    }

    /**
     * Get all configurations for a user
     */
    public List<UserDataSourceConfigDto> getAllConfigs(UUID userId) {
        log.info("Getting all data source configurations for user: {}", userId);
        List<UserDataSourceConfig> configs = repository.findByUserIdOrderByCreatedAtDesc(userId);
        return configs.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * Get active configuration for a specific data source type
     */
    public Optional<UserDataSourceConfigDto> getActiveConfig(UUID userId, UserDataSourceConfig.DataSourceType dataSource) {
        log.info("Getting active {} configuration for user: {}", dataSource, userId);
        return repository.findByUserIdAndDataSourceAndIsActiveTrue(userId, dataSource)
                .map(this::convertToDto);
    }

    /**
     * Get active configuration entity for a specific data source type
     */
    public Optional<UserDataSourceConfig> getActiveConfigEntity(UUID userId, UserDataSourceConfig.DataSourceType dataSource) {
        log.info("Getting active {} configuration entity for user: {}", dataSource, userId);
        return repository.findByUserIdAndDataSourceAndIsActiveTrue(userId, dataSource);
    }

    /**
     * Hot-path lookup for Nightscout credentials. Returns a small immutable record rather than
     * the JPA entity (no lazy fields to trip on outside the tx) and is cached in Caffeine for
     * 5 minutes — eliminates a DB hit on every single Nightscout proxy call.
     *
     * <p>Cached per-user; evicted automatically on save/activate/deactivate/delete of configs.
     */
    @Cacheable(value = CacheConfig.CACHE_NIGHTSCOUT_CREDENTIALS, key = "#userId")
    public Optional<NightscoutCredentials> getNightscoutCredentials(UUID userId) {
        log.debug("Loading Nightscout credentials from DB for user {} (cache miss)", userId);
        return repository
                .findByUserIdAndDataSourceAndIsActiveTrue(userId, UserDataSourceConfig.DataSourceType.NIGHTSCOUT)
                .map(c -> new NightscoutCredentials(
                        c.getNightscoutUrl(),
                        c.getNightscoutApiSecret(),
                        c.getNightscoutApiToken()));
    }

    /**
     * Get configuration status for a user
     */
    public DataSourceConfigStatusDto getConfigStatus(UUID userId) {
        log.info("Getting configuration status for user: {}", userId);
        
        List<UserDataSourceConfig> allConfigs = repository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(userId);
        Optional<UserDataSourceConfig> activeNightscout = repository.findByUserIdAndDataSourceAndIsActiveTrue(userId, UserDataSourceConfig.DataSourceType.NIGHTSCOUT);
        Optional<UserDataSourceConfig> activeLibre = repository.findByUserIdAndDataSourceAndIsActiveTrue(userId, UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP);
        Optional<UserDataSourceConfig> mostRecent = repository.findMostRecentlyUsedConfigByUserId(userId);
        
        return DataSourceConfigStatusDto.builder()
                .hasNightscoutConfig(activeNightscout.isPresent())
                .hasLibreConfig(activeLibre.isPresent())
                .hasAnyConfig(!allConfigs.isEmpty())
                .activeNightscoutConfig(activeNightscout.map(this::convertToDto).orElse(null))
                .activeLibreConfig(activeLibre.map(this::convertToDto).orElse(null))
                .mostRecentlyUsedConfig(mostRecent.map(this::convertToDto).orElse(null))
                .allConfigs(allConfigs.stream().map(this::convertToDto).collect(Collectors.toList()))
                .lastUpdate(allConfigs.isEmpty() ? null : allConfigs.get(0).getUpdatedAt())
                .build();
    }

    /**
     * Activate a specific configuration
     */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_NIGHTSCOUT_CREDENTIALS, key = "#userId")
    public UserDataSourceConfigDto activateConfig(UUID userId, UUID configId) {
        log.info("Activating configuration {} for user: {}", configId, userId);
        
        UserDataSourceConfig config = repository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + configId));
        
        if (!config.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Configuration does not belong to user: " + userId);
        }
        
        // Deactivate other configs of the same type
        repository.deactivateConfigsByUserIdAndDataSource(userId, config.getDataSource());
        
        // Activate this config
        config.setIsActive(true);
        config.updateLastUsed();
        
        UserDataSourceConfig savedConfig = repository.save(config);
        log.info("Successfully activated configuration: {}", configId);
        
        return convertToDto(savedConfig);
    }

    /**
     * Deactivate a specific configuration
     */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_NIGHTSCOUT_CREDENTIALS, key = "#userId")
    public void deactivateConfig(UUID userId, UUID configId) {
        log.info("Deactivating configuration {} for user: {}", configId, userId);
        
        UserDataSourceConfig config = repository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + configId));
        
        if (!config.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Configuration does not belong to user: " + userId);
        }
        
        config.setIsActive(false);
        repository.save(config);
        log.info("Successfully deactivated configuration: {}", configId);
    }

    /**
     * Delete a configuration
     */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_NIGHTSCOUT_CREDENTIALS, key = "#userId")
    public void deleteConfig(UUID userId, UUID configId) {
        log.info("Deleting configuration {} for user: {}", configId, userId);
        
        UserDataSourceConfig config = repository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + configId));
        
        if (!config.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Configuration does not belong to user: " + userId);
        }
        
        repository.delete(config);
        log.info("Successfully deleted configuration: {}", configId);
    }

    /**
     * Update last used timestamp for a configuration
     */
    @Transactional
    public void updateLastUsed(UUID userId, UUID configId) {
        log.debug("Updating last used timestamp for configuration {} for user: {}", configId, userId);
        
        UserDataSourceConfig config = repository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + configId));
        
        if (!config.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Configuration does not belong to user: " + userId);
        }
        
        config.updateLastUsed();
        repository.save(config);
    }

    /**
     * Test a configuration by attempting to connect
     */
    public boolean testConfig(UUID userId, UUID configId) {
        log.info("Testing configuration {} for user: {}", configId, userId);
        
        UserDataSourceConfig config = repository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + configId));
        
        if (!config.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Configuration does not belong to user: " + userId);
        }
        
        // TODO: Implement actual testing logic based on data source type
        // For now, just return true if the config exists and is valid
        try {
            if (config.isNightscoutConfig()) {
                return config.getNightscoutUrl() != null && !config.getNightscoutUrl().trim().isEmpty();
            } else if (config.isLibreConfig()) {
                return config.getLibreEmail() != null && !config.getLibreEmail().trim().isEmpty() &&
                       config.getLibrePassword() != null && !config.getLibrePassword().trim().isEmpty();
            }
        } catch (Exception e) {
            log.error("Error testing configuration {}: {}", configId, e.getMessage());
            return false;
        }
        
        return false;
    }

    /**
     * Validate configuration request
     */
    private void validateConfigRequest(DataSourceConfigRequestDto request) {
        if (request.getDataSource() == null) {
            throw new IllegalArgumentException("Data source type is required");
        }
        
        if (request.isNightscoutConfig()) {
            request.validateNightscoutConfig();
        } else if (request.isLibreConfig()) {
            request.validateLibreConfig();
        }
    }

    /**
     * Convert entity to DTO
     */
    private UserDataSourceConfigDto convertToDto(UserDataSourceConfig config) {
        return UserDataSourceConfigDto.builder()
                .id(config.getId())
                .userId(config.getUser().getId())
                .dataSource(config.getDataSource())
                .nightscoutUrl(config.getNightscoutUrl())
                .nightscoutApiSecret(config.getNightscoutApiSecret())
                .nightscoutApiToken(config.getNightscoutApiToken())
                .libreEmail(config.getLibreEmail())
                .librePassword(config.getLibrePassword())
                .librePatientId(config.getLibrePatientId())
                .isActive(config.getIsActive())
                .lastUsed(config.getLastUsed())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
