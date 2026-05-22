package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.entity.COBSettings;
import che.glucosemonitorbe.repository.COBSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class COBSettingsService {
    
    @Autowired
    private COBSettingsRepository cobSettingsRepository;
    
    /**
     * Get COB settings for a user, creating default settings if none exist
     * @param userId the user ID
     * @return COB settings DTO
     */
    /**
     * Get COB settings for a user, returning defaults if none exist.
     * BUG L3 fix: getCOBSettings must NOT call repository.save — it is a read method
     * that is decorated with @Cacheable. Calling save from a @Cacheable method creates
     * a write-in-read side-effect that breaks caching semantics and causes unexpected
     * DataIntegrityViolationExceptions under concurrency (see C1).
     * Returns a default DTO (not persisted) when no settings row exists.
     */
    @Cacheable(value = "cobSettings", key = "#userId")
    public COBSettingsDTO getCOBSettings(UUID userId) {
        return cobSettingsRepository.findByUserId(userId)
                .map(this::convertToDTO)
                .orElseGet(() -> new COBSettingsDTO(null, userId, 2.0, 1.0, 45, 240));
    }
    
    /**
     * Create or update COB settings for a user
     * @param userId the user ID
     * @param settingsDTO the settings to save
     * @return the saved COB settings DTO
     */
    @CacheEvict(value = "cobSettings", key = "#userId")
    public COBSettingsDTO saveCOBSettings(UUID userId, COBSettingsDTO settingsDTO) {
        Optional<COBSettings> existingSettings = cobSettingsRepository.findByUserId(userId);
        
        COBSettings settings;
        if (existingSettings.isPresent()) {
            settings = existingSettings.get();
            updateSettings(settings, settingsDTO);
        } else {
            settings = new COBSettings();
            settings.setUserId(userId);
            updateSettings(settings, settingsDTO);
        }
        
        COBSettings savedSettings = cobSettingsRepository.save(settings);
        return convertToDTO(savedSettings);
    }
    
    /**
     * Delete COB settings for a user
     * @param userId the user ID
     */
    @CacheEvict(value = "cobSettings", key = "#userId")
    public void deleteCOBSettings(UUID userId) {
        cobSettingsRepository.deleteByUserId(userId);
    }
    
    /**
     * Check if COB settings exist for a user
     * @param userId the user ID
     * @return true if settings exist, false otherwise
     */
    public boolean hasCOBSettings(UUID userId) {
        return cobSettingsRepository.existsByUserId(userId);
    }
    
    /**
     * Create default COB settings for a user.
     * BUG C1 fix: two concurrent threads can both see "not present" and both try to insert,
     * causing a DataIntegrityViolationException on the unique userId constraint.
     * We catch that and re-query so the caller always gets a valid DTO.
     */
    private COBSettingsDTO createDefaultSettings(UUID userId) {
        COBSettings defaultSettings = new COBSettings(userId);
        try {
            COBSettings savedSettings = cobSettingsRepository.save(defaultSettings);
            return convertToDTO(savedSettings);
        } catch (DataIntegrityViolationException e) {
            // Another thread already inserted the row; use that one.
            return cobSettingsRepository.findByUserId(userId)
                    .map(this::convertToDTO)
                    .orElseThrow(() -> new IllegalStateException(
                            "COB settings not found after concurrent insert for user: " + userId, e));
        }
    }
    
    /**
     * Update settings entity with DTO values
     * @param settings the settings entity to update
     * @param settingsDTO the DTO with new values
     */
    private void updateSettings(COBSettings settings, COBSettingsDTO settingsDTO) {
        if (settingsDTO.getCarbRatio() != null) {
            settings.setCarbRatio(settingsDTO.getCarbRatio());
        }
        if (settingsDTO.getIsf() != null) {
            settings.setIsf(settingsDTO.getIsf());
        }
        if (settingsDTO.getCarbHalfLife() != null) {
            settings.setCarbHalfLife(settingsDTO.getCarbHalfLife());
        }
        if (settingsDTO.getMaxCOBDuration() != null) {
            settings.setMaxCOBDuration(settingsDTO.getMaxCOBDuration());
        }
    }
    
    /**
     * Convert entity to DTO
     * @param settings the settings entity
     * @return the settings DTO
     */
    private COBSettingsDTO convertToDTO(COBSettings settings) {
        return new COBSettingsDTO(
            settings.getId(),
            settings.getUserId(),
            settings.getCarbRatio(),
            settings.getIsf(),
            settings.getCarbHalfLife(),
            settings.getMaxCOBDuration()
        );
    }
}
