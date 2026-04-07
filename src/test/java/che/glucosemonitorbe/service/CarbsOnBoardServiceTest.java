package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CarbsOnBoardServiceTest {

    @Test
    void maxCobDurationIsHardCutoff() {
        COBSettingsService settingsService = mock(COBSettingsService.class);
        UUID userId = UUID.randomUUID();
        when(settingsService.getCOBSettings(userId))
                .thenReturn(new COBSettingsDTO(UUID.randomUUID(), userId, 2.0, 1.0, 45, 240));

        CarbsOnBoardService service = new CarbsOnBoardService(settingsService);
        LocalDateTime now = LocalDateTime.now();
        CarbsEntry entry = CarbsEntry.builder()
                .timestamp(now.minusMinutes(250))
                .carbs(50.0)
                .build();

        assertEquals(0.0, service.calculateRemainingCarbs(entry, now, userId), 1e-9);
    }

    @Test
    void enhancedModeStillReturnsBoundedCob() {
        COBSettingsService settingsService = mock(COBSettingsService.class);
        UUID userId = UUID.randomUUID();
        when(settingsService.getCOBSettings(userId))
                .thenReturn(new COBSettingsDTO(UUID.randomUUID(), userId, 2.0, 1.0, 45, 240));
        CarbsOnBoardService service = new CarbsOnBoardService(settingsService);
        LocalDateTime now = LocalDateTime.now();
        CarbsEntry entry = CarbsEntry.builder()
                .timestamp(now.minusMinutes(40))
                .carbs(60.0)
                .build();
        entry.setAbsorptionMode("GI_GL_ENHANCED");
        entry.setEstimatedGi(70.0);
        entry.setFiber(5.0);
        entry.setProtein(12.0);
        entry.setFat(10.0);
        double remaining = service.calculateRemainingCarbs(entry, now, userId);
        assertTrue(remaining >= 0.0 && remaining <= 60.0);
    }
}

