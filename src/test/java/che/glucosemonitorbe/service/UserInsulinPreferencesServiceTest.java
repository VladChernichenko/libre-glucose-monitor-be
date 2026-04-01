package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.UpdateUserInsulinPreferencesRequest;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.entity.InsulinCatalog;
import che.glucosemonitorbe.entity.UserInsulinPreferences;
import che.glucosemonitorbe.repository.UserInsulinPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class UserInsulinPreferencesServiceTest {

    @Mock
    private UserInsulinPreferencesRepository userInsulinPreferencesRepository;

    @Mock
    private InsulinCatalogService insulinCatalogService;

    @InjectMocks
    private UserInsulinPreferencesService service;

    @Test
    void getPreferencesReturnsDefaultsWhenMissing() {
        UUID userId = UUID.randomUUID();
        InsulinCatalog rapid = insulin("FIASP", InsulinCatalog.Category.RAPID);
        InsulinCatalog longActing = insulin("TRESIBA", InsulinCatalog.Category.LONG_ACTING);
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(insulinCatalogService.getRequiredByCode("FIASP")).thenReturn(rapid);
        when(insulinCatalogService.getRequiredByCode("TRESIBA")).thenReturn(longActing);

        UserInsulinPreferencesDTO result = service.getPreferences(userId);

        assertEquals("FIASP", result.getRapidInsulinCode());
        assertEquals("TRESIBA", result.getLongActingInsulinCode());
    }

    @Test
    void savePreferencesRejectsWrongCategory() {
        UUID userId = UUID.randomUUID();
        UpdateUserInsulinPreferencesRequest request = new UpdateUserInsulinPreferencesRequest();
        request.setRapidInsulinCode("fiasp");
        request.setLongActingInsulinCode("tresiba");

        InsulinCatalog wrongRapid = insulin("FIASP", InsulinCatalog.Category.LONG_ACTING);
        InsulinCatalog longActing = insulin("TRESIBA", InsulinCatalog.Category.LONG_ACTING);
        when(insulinCatalogService.getRequiredByCode("FIASP")).thenReturn(wrongRapid);
        when(insulinCatalogService.getRequiredByCode("TRESIBA")).thenReturn(longActing);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.savePreferences(userId, request));
        assertTrue(ex.getMessage().contains("Rapid insulin"));
    }

    @Test
    void savePreferencesPersistsAndMaps() {
        UUID userId = UUID.randomUUID();
        UpdateUserInsulinPreferencesRequest request = new UpdateUserInsulinPreferencesRequest();
        request.setRapidInsulinCode("FIASP");
        request.setLongActingInsulinCode("TRESIBA");

        InsulinCatalog rapid = insulin("FIASP", InsulinCatalog.Category.RAPID);
        InsulinCatalog longActing = insulin("TRESIBA", InsulinCatalog.Category.LONG_ACTING);
        when(insulinCatalogService.getRequiredByCode("FIASP")).thenReturn(rapid);
        when(insulinCatalogService.getRequiredByCode("TRESIBA")).thenReturn(longActing);
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());

        UserInsulinPreferences saved = UserInsulinPreferences.builder()
                .userId(userId)
                .rapidInsulin(rapid)
                .longActingInsulin(longActing)
                .build();
        when(userInsulinPreferencesRepository.save(any(UserInsulinPreferences.class))).thenReturn(saved);

        UserInsulinPreferencesDTO result = service.savePreferences(userId, request);

        assertEquals("FIASP", result.getRapidInsulinCode());
        assertEquals("TRESIBA", result.getLongActingInsulinCode());
    }

    private static InsulinCatalog insulin(String code, InsulinCatalog.Category category) {
        return InsulinCatalog.builder()
                .code(code)
                .category(category)
                .displayName(code)
                .diaHours(4.5)
                .halfLifeMinutes(60.0)
                .build();
    }
}
