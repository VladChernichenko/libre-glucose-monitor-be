package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.entity.COBSettings;
import che.glucosemonitorbe.repository.COBSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
    public COBSettingsDTO getCOBSettings(UUID userId) {
        Optional<COBSettings> settings = cobSettingsRepository.findByUserId(userId);
        
        if (settings.isPresent()) {
            return convertToDTO(settings.get());
        } else {
            // Create default settings for the user
            return createDefaultSettings(userId);
        }
    }
    
    /**
     * Create or update COB settings for a user
     * @param userId the user ID
     * @param settingsDTO the settings to save
     * @return the saved COB settings DTO
     */
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
     * Create default COB settings for a user
     * @param userId the user ID
     * @return the created COB settings DTO
     */
    private COBSettingsDTO createDefaultSettings(UUID userId) {
        COBSettings defaultSettings = new COBSettings(userId);
        COBSettings savedSettings = cobSettingsRepository.save(defaultSettings);
        return convertToDTO(savedSettings);
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
