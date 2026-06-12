package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.entity.COBSettings;
import che.glucosemonitorbe.repository.COBSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class COBSettingsService {

    private final COBSettingsRepository cobSettingsRepository;
    
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
        log.info("saveCOBSettings request for userId={}: carbRatio={}, isf={}, carbHalfLife={}, " +
                        "maxCOBDuration={}, bodyWeightKg={}, isfBreakfast={}, isfLunch={}, isfDinner={}",
                userId, settingsDTO.getCarbRatio(), settingsDTO.getIsf(), settingsDTO.getCarbHalfLife(),
                settingsDTO.getMaxCOBDuration(), settingsDTO.getBodyWeightKg(),
                settingsDTO.getIsfBreakfast(), settingsDTO.getIsfLunch(), settingsDTO.getIsfDinner());

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
        log.info("saveCOBSettings persisted for userId={}: isf={}, isfBreakfast={}, isfLunch={}, isfDinner={}",
                userId, savedSettings.getIsf(), savedSettings.getIsfBreakfast(),
                savedSettings.getIsfLunch(), savedSettings.getIsfDinner());
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
        // bodyWeightKg is optional — only update if explicitly provided
        if (settingsDTO.getBodyWeightKg() != null) {
            settings.setBodyWeightKg(settingsDTO.getBodyWeightKg());
        }
        // Manual per-meal-window ISF overrides — only update if explicitly provided.
        if (settingsDTO.getIsfBreakfast() != null) {
            settings.setIsfBreakfast(settingsDTO.getIsfBreakfast());
        }
        if (settingsDTO.getIsfLunch() != null) {
            settings.setIsfLunch(settingsDTO.getIsfLunch());
        }
        if (settingsDTO.getIsfDinner() != null) {
            settings.setIsfDinner(settingsDTO.getIsfDinner());
        }
    }

    /**
     * Convert entity to DTO.
     */
    private COBSettingsDTO convertToDTO(COBSettings settings) {
        return new COBSettingsDTO(
            settings.getId(),
            settings.getUserId(),
            settings.getCarbRatio(),
            settings.getIsf(),
            settings.getCarbHalfLife(),
            settings.getMaxCOBDuration(),
            settings.getBodyWeightKg(),
            settings.getIsfBreakfast(),
            settings.getIsfLunch(),
            settings.getIsfDinner()
        );
    }
}
