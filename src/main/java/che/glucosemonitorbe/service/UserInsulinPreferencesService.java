package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.InsulinCatalogDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UpdateUserInsulinPreferencesRequest;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.entity.InsulinCatalog;
import che.glucosemonitorbe.entity.UserInsulinPreferences;
import che.glucosemonitorbe.repository.UserInsulinPreferencesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserInsulinPreferencesService {

    public static final String DEFAULT_RAPID_CODE = "FIASP";
    public static final String DEFAULT_LONG_ACTING_CODE = "TRESIBA";

    private final UserInsulinPreferencesRepository userInsulinPreferencesRepository;
    private final InsulinCatalogService insulinCatalogService;

    @Transactional(readOnly = true)
    public UserInsulinPreferencesDTO getPreferences(UUID userId) {
        UserInsulinPreferences prefs = userInsulinPreferencesRepository.findById(userId)
                .orElse(null);
        if (prefs == null) {
            return buildDtoWithCatalogOnly(DEFAULT_RAPID_CODE, DEFAULT_LONG_ACTING_CODE);
        }
        return toDto(prefs);
    }

    /**
     * Resolves rapid-acting insulin curve parameters for bolus IOB. Peak defaults to 75 min if unset.
     */
    @Transactional(readOnly = true)
    public RapidInsulinIobParameters getRapidIobParameters(UUID userId) {
        InsulinCatalog rapid = userInsulinPreferencesRepository.findById(userId)
                .map(UserInsulinPreferences::getRapidInsulin)
                .orElseGet(() -> insulinCatalogService.getRequiredByCode(DEFAULT_RAPID_CODE));

        if (rapid.getCategory() != InsulinCatalog.Category.RAPID) {
            rapid = insulinCatalogService.getRequiredByCode(DEFAULT_RAPID_CODE);
        }

        double dia = rapid.getDiaHours() != null && rapid.getDiaHours() > 0 ? rapid.getDiaHours() : 4.5;
        double peak = rapid.getPeakMinutes() != null && rapid.getPeakMinutes() > 0
                ? rapid.getPeakMinutes()
                : 75.0;

        double endMin = dia * 60.0;
        if (peak >= endMin / 2.0 - 1e-3) {
            peak = Math.min(peak, endMin / 2.0 - 5.0);
            peak = Math.max(peak, 15.0);
        }
        return new RapidInsulinIobParameters(dia, peak);
    }

    @Transactional
    public UserInsulinPreferencesDTO savePreferences(UUID userId, UpdateUserInsulinPreferencesRequest request) {
        InsulinCatalog rapid = insulinCatalogService.getRequiredByCode(request.getRapidInsulinCode().trim().toUpperCase());
        InsulinCatalog basal = insulinCatalogService.getRequiredByCode(request.getLongActingInsulinCode().trim().toUpperCase());

        if (rapid.getCategory() != InsulinCatalog.Category.RAPID) {
            throw new IllegalArgumentException("Rapid insulin must be a RAPID category type: " + rapid.getCode());
        }
        if (basal.getCategory() != InsulinCatalog.Category.LONG_ACTING) {
            throw new IllegalArgumentException("Long-acting insulin must be LONG_ACTING category: " + basal.getCode());
        }

        UserInsulinPreferences entity = userInsulinPreferencesRepository.findById(userId)
                .orElse(UserInsulinPreferences.builder().userId(userId).build());
        entity.setUserId(userId);
        entity.setRapidInsulin(rapid);
        entity.setLongActingInsulin(basal);

        UserInsulinPreferences saved = userInsulinPreferencesRepository.save(entity);
        return toDto(saved);
    }

    private UserInsulinPreferencesDTO buildDtoWithCatalogOnly(String rapidCode, String longCode) {
        InsulinCatalog rapid = insulinCatalogService.getRequiredByCode(rapidCode);
        InsulinCatalog basal = insulinCatalogService.getRequiredByCode(longCode);
        return UserInsulinPreferencesDTO.builder()
                .rapidInsulinCode(rapid.getCode())
                .longActingInsulinCode(basal.getCode())
                .rapidInsulin(InsulinCatalogService.toDto(rapid))
                .longActingInsulin(InsulinCatalogService.toDto(basal))
                .build();
    }

    private UserInsulinPreferencesDTO toDto(UserInsulinPreferences p) {
        return UserInsulinPreferencesDTO.builder()
                .rapidInsulinCode(p.getRapidInsulin().getCode())
                .longActingInsulinCode(p.getLongActingInsulin().getCode())
                .rapidInsulin(InsulinCatalogService.toDto(p.getRapidInsulin()))
                .longActingInsulin(InsulinCatalogService.toDto(p.getLongActingInsulin()))
                .build();
    }
}
