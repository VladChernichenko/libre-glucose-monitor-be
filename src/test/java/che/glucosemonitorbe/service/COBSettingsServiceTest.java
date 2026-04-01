package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.entity.COBSettings;
import che.glucosemonitorbe.repository.COBSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class COBSettingsServiceTest {

    @Mock
    private COBSettingsRepository repository;

    @InjectMocks
    private COBSettingsService service;

    @Test
    void getCOBSettingsCreatesDefaultsWhenMissing() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.save(any(COBSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        COBSettingsDTO result = service.getCOBSettings(userId);

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
        assertEquals(2.0, result.getCarbRatio());
        assertEquals(45, result.getCarbHalfLife());
    }

    @Test
    void saveCOBSettingsUpdatesExistingEntity() {
        UUID userId = UUID.randomUUID();
        COBSettings existing = new COBSettings(userId, 2.0, 1.0, 45, 240);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        COBSettingsDTO request = new COBSettingsDTO();
        request.setCarbRatio(1.5);
        request.setIsf(0.9);
        request.setCarbHalfLife(50);
        request.setMaxCOBDuration(300);

        COBSettingsDTO result = service.saveCOBSettings(userId, request);

        assertEquals(1.5, result.getCarbRatio());
        assertEquals(0.9, result.getIsf());
        assertEquals(50, result.getCarbHalfLife());
        assertEquals(300, result.getMaxCOBDuration());
    }

    @Test
    void deleteAndExistsDelegateToRepository() {
        UUID userId = UUID.randomUUID();
        when(repository.existsByUserId(userId)).thenReturn(false);

        service.deleteCOBSettings(userId);
        boolean exists = service.hasCOBSettings(userId);

        verify(repository).deleteByUserId(userId);
        assertFalse(exists);
    }
}
