package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.NightscoutConfig;
import che.glucosemonitorbe.dto.NightscoutConfigRequestDto;
import che.glucosemonitorbe.dto.NightscoutConfigResponseDto;
import che.glucosemonitorbe.repository.NightscoutConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class NightscoutConfigService {
    
    private final NightscoutConfigRepository repository;
    
    /**
     * Create or update Nightscout configuration for a user
     */
    @Transactional
    public NightscoutConfigResponseDto saveConfig(UUID userId, NightscoutConfigRequestDto request) {
        log.info("Saving Nightscout configuration for user {}", userId);
        log.info("Request details - URL: {}, API Secret present: {}, API Token present: {}, IsActive: {}", 
                request.getNightscoutUrl(), 
                request.getApiSecret() != null && !request.getApiSecret().isEmpty(),
                request.getApiToken() != null && !request.getApiToken().isEmpty(),
                request.getIsActive());
        
        try {
            // Check if user already has a configuration
            Optional<NightscoutConfig> existingConfig = repository.findByUserId(userId);
            log.info("Existing configuration found: {}", existingConfig.isPresent());
            
            NightscoutConfig config;
            if (existingConfig.isPresent()) {
                // Update existing configuration
                config = existingConfig.get();
                log.info("Updating existing config with ID: {}", config.getId());
                config.setNightscoutUrl(request.getNightscoutUrl());
                config.setApiSecret(request.getApiSecret());
                config.setApiToken(request.getApiToken());
                config.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
                config.setUpdatedAt(LocalDateTime.now());
                log.info("Updated existing Nightscout configuration for user {}", userId);
            } else {
                // Create new configuration
                config = NightscoutConfig.builder()
                        .userId(userId)
                        .nightscoutUrl(request.getNightscoutUrl())
                        .apiSecret(request.getApiSecret())
                        .apiToken(request.getApiToken())
                        .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                log.info("Created new Nightscout configuration for user {}", userId);
            }
            
            log.info("About to save config to database...");
            NightscoutConfig savedConfig = repository.save(config);
            log.info("Successfully saved config with ID: {} for user: {}", savedConfig.getId(), userId);
            
            NightscoutConfigResponseDto responseDto = convertToResponseDto(savedConfig);
            log.info("Converted to response DTO with ID: {}", responseDto.getId());
            
            return responseDto;
        } catch (Exception e) {
            log.error("Error saving Nightscout configuration for user {}: {}", userId, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Get Nightscout configuration for a user
     */
    public Optional<NightscoutConfigResponseDto> getConfig(UUID userId) {
        log.debug("Getting Nightscout configuration for user {}", userId);
        return repository.findByUserId(userId)
                .map(this::convertToResponseDto);
    }
    
    /**
     * Get active Nightscout configuration for a user
     */
    public Optional<NightscoutConfigResponseDto> getActiveConfig(UUID userId) {
        log.debug("Getting active Nightscout configuration for user {}", userId);
        return repository.findByUserIdAndIsActiveTrue(userId)
                .map(this::convertToResponseDto);
    }
    
    /**
     * Get the actual configuration (with unmasked secrets) for API calls
     */
    public Optional<NightscoutConfig> getConfigForApiCalls(UUID userId) {
        log.debug("Getting Nightscout configuration for API calls for user {}", userId);
        return repository.findByUserIdAndIsActiveTrue(userId);
    }
    
    /**
     * Test Nightscout configuration by updating last used timestamp
     */
    @Transactional
    public void markAsUsed(UUID userId) {
        log.debug("Marking Nightscout configuration as used for user {}", userId);
        repository.updateLastUsed(userId, LocalDateTime.now());
    }
    
    /**
     * Deactivate Nightscout configuration for a user
     */
    @Transactional
    public void deactivateConfig(UUID userId) {
        log.info("Deactivating Nightscout configuration for user {}", userId);
        repository.deactivateByUserId(userId);
    }
    
    /**
     * Delete Nightscout configuration for a user
     */
    @Transactional
    public void deleteConfig(UUID userId) {
        log.info("Deleting Nightscout configuration for user {}", userId);
        repository.deleteByUserId(userId);
    }
    
    /**
     * Check if user has a Nightscout configuration
     */
    public boolean hasConfig(UUID userId) {
        return repository.existsByUserId(userId);
    }
    
    /**
     * Get all active configurations (for admin purposes)
     */
    public List<NightscoutConfigResponseDto> getAllActiveConfigs() {
        log.debug("Getting all active Nightscout configurations");
        return repository.findByIsActiveTrue()
                .stream()
                .map(this::convertToResponseDto)
                .collect(Collectors.toList());
    }
    
    /**
     * Get configuration count
     */
    public long getActiveConfigCount() {
        return repository.countByIsActiveTrue();
    }
    
    /**
     * Convert entity to response DTO with masked sensitive data
     */
    private NightscoutConfigResponseDto convertToResponseDto(NightscoutConfig config) {
        NightscoutConfigResponseDto dto = NightscoutConfigResponseDto.builder()
                .id(config.getId())
                .nightscoutUrl(config.getNightscoutUrl())
                .apiSecret(config.getApiSecret())
                .apiToken(config.getApiToken())
                .isActive(config.getIsActive())
                .lastUsed(config.getLastUsed())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
        
        return NightscoutConfigResponseDto.maskSensitiveData(dto);
    }
}

