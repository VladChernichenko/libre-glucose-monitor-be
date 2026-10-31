package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.domain.RapidInsulinLogged;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RapidInsulinPlanSupersederTest {

    @Test
    @DisplayName("a rapid-insulin note supersedes PLANNED plans at the user's wall time, excluding its own plan")
    void supersedesWithUserWallTime() {
        MealActionService actions = mock(MealActionService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        UUID user = UUID.randomUUID();
        UUID note = UUID.randomUUID();
        UUID chat = UUID.randomUUID();
        UserSettingsDTO s = new UserSettingsDTO();
        s.setTimezone("Asia/Tbilisi");
        when(settings.getUserSettings(user)).thenReturn(s);

        new RapidInsulinPlanSuperseder(actions, settings).onRapidInsulin(new RapidInsulinLogged(user, note, chat));

        verify(actions).supersedeForRapidInsulin(eq(user), eq(note), eq(chat), argThat((LocalDateTime t) -> {
            LocalDateTime tbilisi = LocalDateTime.now(ZoneId.of("Asia/Tbilisi"));
            return !t.isAfter(tbilisi) && t.isAfter(tbilisi.minusMinutes(1));
        }));
        assertThat(mockingDetails(actions).getInvocations()).hasSize(1);
    }
}
