package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.domain.PredictionEngine;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.UserSettings;
import che.glucosemonitorbe.exception.InvalidConfigurationException;
import che.glucosemonitorbe.repository.UserSettingsRepository;
import che.glucosemonitorbe.sick.SickProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserSettingsService {

    private final UserSettingsRepository userSettingsRepository;
    private final FeatureToggleConfig featureToggleConfig;
    private final SickProfileService sickProfileService;
    private final CacheManager cacheManager;

    private static final String USER_SETTINGS_CACHE = "userSettings";
    
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
    public UserSettingsDTO getUserSettings(UUID userId) {
        UserSettingsDTO raw = rawSettings(userId);
        ZoneId zone = UserZones.resolve(raw.getTimezone(), raw.getUtcOffsetMinutes());
        // The illness profile is read on every call, outside the cache: it changes on commands that
        // race with readers, and a stale cached profile would misprice doses until evicted.
        return raw.withSickProfile(sickProfileService.profile(userId, zone));
    }

    /**
     * The settings without the illness profile, cached in the {@code userSettings} cache. Programmatic
     * rather than {@code @Cacheable}: a self-call would bypass the Spring proxy, and a separate bean
     * would only move {@code convertToDTO}. The key matches the {@code @CacheEvict} methods.
     */
    private UserSettingsDTO rawSettings(UUID userId) {
        Cache cache = cacheManager.getCache(USER_SETTINGS_CACHE);
        return cache == null ? loadRaw(userId) : cache.get(userId, () -> loadRaw(userId));
    }

    private UserSettingsDTO loadRaw(UUID userId) {
        return userSettingsRepository.findByUserId(userId)
                .map(this::convertToDTO)
                .orElseGet(() -> {
                    UserSettingsDTO d = new UserSettingsDTO(null, userId, 2.0, 1.0, 45, 240);
                    d.setGramsPerUnitBreakfast(UserSettings.DEFAULT_GRAMS_PER_UNIT);
                    d.setGramsPerUnitLunch(UserSettings.DEFAULT_GRAMS_PER_UNIT);
                    d.setGramsPerUnitDinner(UserSettings.DEFAULT_GRAMS_PER_UNIT);
                    d.setGramsPerUnitNight(UserSettings.DEFAULT_GRAMS_PER_UNIT);
                    d.setTargetGlucoseMmol(UserSettingsDTO.DEFAULT_TARGET_GLUCOSE_MMOL);
                    d.setRescueCarbsG(15);
                    return d;
                });
    }

    /** Drops the cached settings. Not needed for the illness profile, which is never cached. */
    @CacheEvict(value = "userSettings", key = "#userId")
    public void evict(UUID userId) {
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
                        "maxCOBDuration={}, bodyWeightKg={}, isfBreakfast={}, isfLunch={}, isfDinner={}, isfNight={}, " +
                        "gramsPerUnitBreakfast={}, gramsPerUnitLunch={}, gramsPerUnitDinner={}, gramsPerUnitNight={}",
                userId, settingsDTO.getCarbRatio(), settingsDTO.getIsf(), settingsDTO.getCarbHalfLife(),
                settingsDTO.getMaxCOBDuration(), settingsDTO.getBodyWeightKg(),
                settingsDTO.getIsfBreakfast(), settingsDTO.getIsfLunch(), settingsDTO.getIsfDinner(),
                settingsDTO.getIsfNight(),
                settingsDTO.getGramsPerUnitBreakfast(), settingsDTO.getGramsPerUnitLunch(),
                settingsDTO.getGramsPerUnitDinner(), settingsDTO.getGramsPerUnitNight());

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
        log.info("saveUserSettings persisted for userId={}: isf={}, isfBreakfast={}, isfLunch={}, isfDinner={}, isfNight={}, " +
                        "gramsPerUnitBreakfast={}, gramsPerUnitLunch={}, gramsPerUnitDinner={}, gramsPerUnitNight={}",
                userId, savedSettings.getIsf(), savedSettings.getIsfBreakfast(),
                savedSettings.getIsfLunch(), savedSettings.getIsfDinner(), savedSettings.getIsfNight(),
                savedSettings.getGramsPerUnitBreakfast(), savedSettings.getGramsPerUnitLunch(),
                savedSettings.getGramsPerUnitDinner(), savedSettings.getGramsPerUnitNight());
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
        if (settingsDTO.getGramsPerUnitBreakfast() != null) {
            settings.setGramsPerUnitBreakfast(settingsDTO.getGramsPerUnitBreakfast());
        }
        if (settingsDTO.getGramsPerUnitLunch() != null) {
            settings.setGramsPerUnitLunch(settingsDTO.getGramsPerUnitLunch());
        }
        if (settingsDTO.getGramsPerUnitDinner() != null) {
            settings.setGramsPerUnitDinner(settingsDTO.getGramsPerUnitDinner());
        }
        if (settingsDTO.getGramsPerUnitNight() != null) {
            settings.setGramsPerUnitNight(settingsDTO.getGramsPerUnitNight());
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
        if (settingsDTO.getTargetGlucoseMmol() != null) {
            settings.setTargetGlucoseMmol(settingsDTO.getTargetGlucoseMmol());
        }
        if (settingsDTO.getRescueCarbsG() != null) {
            settings.setRescueCarbsG(settingsDTO.getRescueCarbsG());
        }
        if (settingsDTO.getPredictionEngine() != null) {
            if (settingsDTO.getPredictionEngine() != PredictionEngine.HOVORKA
                    && !featureToggleConfig.isEmpiricalTwinEnabled()) {
                throw new InvalidConfigurationException(
                        "Prediction engine " + settingsDTO.getPredictionEngine() + " is not enabled");
            }
            settings.setPredictionEngine(settingsDTO.getPredictionEngine());
        }
    }

    /**
     * Convert entity to DTO.
     */
    /**
     * Records the user's time zone (IANA id, plus the raw offset as a fallback) observed on a
     * dashboard request.
     *
     * <p>The nightly digital-twin calibration is a batch job with no request context, but it must
     * reconcile {@code notes.timestamp} (local wall time) with {@code cgm_readings.date_timestamp}
     * (true UTC epochs). This is the only place the offset is observable, so it is captured here.
     * Callers must invoke this only when a value actually changes - the dashboard polls every
     * 30 s and an unconditional write would evict the settings cache on every poll.</p>
     */
    @CacheEvict(value = "userSettings", key = "#userId")
    @Transactional
    public void recordClientZone(UUID userId, String timezone, Integer offsetMinutes) {
        if (timezone == null && offsetMinutes == null) return;
        userSettingsRepository.findByUserId(userId).ifPresent(settings -> {
            if (timezone != null)      settings.setTimezone(timezone);
            if (offsetMinutes != null) settings.setUtcOffsetMinutes(offsetMinutes);
            userSettingsRepository.save(settings);
            log.info("Recorded client zone tz={} offset={} min for user {}",
                    timezone, offsetMinutes, userId);
        });
    }

    private UserSettingsDTO convertToDTO(UserSettings settings) {
        UserSettingsDTO dto = new UserSettingsDTO(
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
        dto.setTimezone(settings.getTimezone());
        dto.setUtcOffsetMinutes(settings.getUtcOffsetMinutes());
        dto.setPredictionEngine(settings.getPredictionEngine());
        dto.setTargetGlucoseMmol(settings.getTargetGlucoseMmol());
        dto.setRescueCarbsG(settings.getRescueCarbsG());
        dto.setGramsPerUnitBreakfast(settings.getGramsPerUnitBreakfast());
        dto.setGramsPerUnitLunch(settings.getGramsPerUnitLunch());
        dto.setGramsPerUnitDinner(settings.getGramsPerUnitDinner());
        dto.setGramsPerUnitNight(settings.getGramsPerUnitNight());
        return dto;
    }
}
