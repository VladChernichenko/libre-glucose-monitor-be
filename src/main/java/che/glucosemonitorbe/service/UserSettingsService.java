package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.UserSettings;
import che.glucosemonitorbe.repository.UserSettingsRepository;
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
public class UserSettingsService {

    private final UserSettingsRepository userSettingsRepository;
    
    /**
     * Get COB settings for a user, creating default settings if none exist
     * @param userId the user ID
     * @return COB settings DTO
     */
    /**
     * Get COB settings for a user, returning defaults if none exist.
     * BUG L3 fix: getUserSettings must NOT call repository.save - it is a read method
     * that is decorated with @Cacheable. Calling save from a @Cacheable method creates
     * a write-in-read side-effect that breaks caching semantics and causes unexpected
     * DataIntegrityViolationExceptions under concurrency (see C1).
     * Returns a default DTO (not persisted) when no settings row exists.
     */
    @Cacheable(value = "userSettings", key = "#userId")
    public UserSettingsDTO getUserSettings(UUID userId) {
        return userSettingsRepository.findByUserId(userId)
                .map(this::convertToDTO)
                .orElseGet(() -> new UserSettingsDTO(null, userId, 2.0, 1.0, 45, 240));
    }
    
    /**
     * Create or update COB settings for a user
     * @param userId the user ID
     * @param settingsDTO the settings to save
     * @return the saved COB settings DTO
     */
    @CacheEvict(value = "userSettings", key = "#userId")
    public UserSettingsDTO saveUserSettings(UUID userId, UserSettingsDTO settingsDTO) {
        log.info("saveUserSettings request for userId={}: carbRatio={}, isf={}, carbHalfLife={}, " +
                        "maxCOBDuration={}, bodyWeightKg={}, isfBreakfast={}, isfLunch={}, isfDinner={}, isfNight={}",
                userId, settingsDTO.getCarbRatio(), settingsDTO.getIsf(), settingsDTO.getCarbHalfLife(),
                settingsDTO.getMaxCOBDuration(), settingsDTO.getBodyWeightKg(),
                settingsDTO.getIsfBreakfast(), settingsDTO.getIsfLunch(), settingsDTO.getIsfDinner(),
                settingsDTO.getIsfNight());

        Optional<UserSettings> existingSettings = userSettingsRepository.findByUserId(userId);

        UserSettings settings;
        if (existingSettings.isPresent()) {
            settings = existingSettings.get();
            updateSettings(settings, settingsDTO);
        } else {
            settings = new UserSettings();
            settings.setUserId(userId);
            updateSettings(settings, settingsDTO);
        }

        UserSettings savedSettings = userSettingsRepository.save(settings);
        log.info("saveUserSettings persisted for userId={}: isf={}, isfBreakfast={}, isfLunch={}, isfDinner={}, isfNight={}",
                userId, savedSettings.getIsf(), savedSettings.getIsfBreakfast(),
                savedSettings.getIsfLunch(), savedSettings.getIsfDinner(), savedSettings.getIsfNight());
        return convertToDTO(savedSettings);
    }
    
    /**
     * Delete COB settings for a user
     * @param userId the user ID
     */
    @CacheEvict(value = "userSettings", key = "#userId")
    public void deleteUserSettings(UUID userId) {
        userSettingsRepository.deleteByUserId(userId);
    }
    
    /**
     * Check if COB settings exist for a user
     * @param userId the user ID
     * @return true if settings exist, false otherwise
     */
    public boolean hasUserSettings(UUID userId) {
        return userSettingsRepository.existsByUserId(userId);
    }
    
    /**
     * Update settings entity with DTO values
     * @param settings the settings entity to update
     * @param settingsDTO the DTO with new values
     */
    private void updateSettings(UserSettings settings, UserSettingsDTO settingsDTO) {
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
        // bodyWeightKg is optional - only update if explicitly provided
        if (settingsDTO.getBodyWeightKg() != null) {
            settings.setBodyWeightKg(settingsDTO.getBodyWeightKg());
        }
        // Manual per-meal-window ISF overrides - only update if explicitly provided.
        if (settingsDTO.getIsfBreakfast() != null) {
            settings.setIsfBreakfast(settingsDTO.getIsfBreakfast());
        }
        if (settingsDTO.getIsfLunch() != null) {
            settings.setIsfLunch(settingsDTO.getIsfLunch());
        }
        if (settingsDTO.getIsfDinner() != null) {
            settings.setIsfDinner(settingsDTO.getIsfDinner());
        }
        if (settingsDTO.getIsfNight() != null) {
            settings.setIsfNight(settingsDTO.getIsfNight());
        }
    }

    /**
     * Convert entity to DTO.
     */
    private UserSettingsDTO convertToDTO(UserSettings settings) {
        return new UserSettingsDTO(
            settings.getId(),
            settings.getUserId(),
            settings.getCarbRatio(),
            settings.getIsf(),
            settings.getCarbHalfLife(),
            settings.getMaxCOBDuration(),
            settings.getBodyWeightKg(),
            settings.getIsfBreakfast(),
            settings.getIsfLunch(),
            settings.getIsfDinner(),
            settings.getIsfNight()
        );
    }
}
