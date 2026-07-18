package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.IsfMealWindowDTO;
import che.glucosemonitorbe.dto.IsfMealWindowProfileResponse;
import che.glucosemonitorbe.dto.IsfMealWindowSuggestionDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.IsfMealWindowSuggestion;
import che.glucosemonitorbe.entity.UserDigitalTwin;
import che.glucosemonitorbe.repository.IsfMealWindowSuggestionRepository;
import che.glucosemonitorbe.repository.UserDigitalTwinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsfMealWindowSuggestionServiceTest {

    @Mock private IsfMealWindowProfileService profileService;
    @Mock private UserSettingsService userSettingsService;
    @Mock private UserDigitalTwinRepository twinRepository;
    @Mock private IsfMealWindowSuggestionRepository suggestionRepository;

    private IsfMealWindowSuggestionService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new IsfMealWindowSuggestionService(
                profileService, userSettingsService, twinRepository, suggestionRepository);
    }

    @Test
    @DisplayName("show=true when twin fitted, 2+ windows with data, material delta, cadence clear")
    void show_whenReady() {
        stubTwinFitted();
        stubProfile(window("BREAKFAST", 2.8, true), window("LUNCH", 2.1, true),
                window("DINNER", null, false), window("NIGHT", null, false));
        stubSettings(2.5);
        when(suggestionRepository.findById(userId)).thenReturn(Optional.empty());

        IsfMealWindowSuggestionDTO dto = service.getSuggestion(userId);

        assertThat(dto.isShow()).isTrue();
        assertThat(dto.getSuppressReason()).isNull();
        assertThat(dto.getCadenceDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("suppress when twin never fitted")
    void suppress_twinNotReady() {
        when(twinRepository.findByUserId(userId)).thenReturn(Optional.empty());
        stubProfile(window("BREAKFAST", 2.8, true), window("LUNCH", 2.1, true),
                window("DINNER", null, false), window("NIGHT", null, false));
        stubSettings(2.5);
        when(suggestionRepository.findById(userId)).thenReturn(Optional.empty());

        IsfMealWindowSuggestionDTO dto = service.getSuggestion(userId);

        assertThat(dto.isShow()).isFalse();
        assertThat(dto.getSuppressReason()).isEqualTo("twin_not_ready");
    }

    @Test
    @DisplayName("suppress within 3-day cadence after dismiss")
    void suppress_cadence() {
        stubTwinFitted();
        stubProfile(window("BREAKFAST", 2.8, true), window("LUNCH", 2.1, true),
                window("DINNER", null, false), window("NIGHT", null, false));
        stubSettings(2.5);
        when(suggestionRepository.findById(userId)).thenReturn(Optional.of(
                IsfMealWindowSuggestion.builder()
                        .userId(userId)
                        .lastDismissedAt(LocalDateTime.now().minusDays(1))
                        .build()));

        IsfMealWindowSuggestionDTO dto = service.getSuggestion(userId);

        assertThat(dto.isShow()).isFalse();
        assertThat(dto.getSuppressReason()).isEqualTo("cadence");
        assertThat(dto.getNextEligibleAt()).isNotNull();
    }

    @Test
    @DisplayName("accept writes proposed ISF into settings and records timestamp")
    void accept_persistsSettings() {
        stubTwinFitted();
        stubProfile(window("BREAKFAST", 2.8, true), window("LUNCH", 2.1, true),
                window("DINNER", null, false), window("NIGHT", null, false));
        stubSettings(2.5);
        when(suggestionRepository.findById(userId)).thenReturn(Optional.empty());
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.accept(userId);

        ArgumentCaptor<UserSettingsDTO> cap = ArgumentCaptor.forClass(UserSettingsDTO.class);
        verify(userSettingsService).saveUserSettings(eq(userId), cap.capture());
        assertThat(cap.getValue().getIsfBreakfast()).isEqualTo(2.8);
        assertThat(cap.getValue().getIsfLunch()).isEqualTo(2.1);

        ArgumentCaptor<IsfMealWindowSuggestion> row = ArgumentCaptor.forClass(IsfMealWindowSuggestion.class);
        verify(suggestionRepository).save(row.capture());
        assertThat(row.getValue().getLastAcceptedAt()).isNotNull();
    }

    private void stubTwinFitted() {
        when(twinRepository.findByUserId(userId)).thenReturn(Optional.of(
                UserDigitalTwin.builder()
                        .userId(userId)
                        .fittedAt(LocalDateTime.now().minusDays(1))
                        .applied(true)
                        .trainSamples(300)
                        .build()));
    }

    private void stubSettings(double isf) {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setIsf(isf);
        s.setCarbRatio(2.0);
        s.setCarbHalfLife(45);
        s.setMaxCOBDuration(240);
        when(userSettingsService.getUserSettings(userId)).thenReturn(s);
    }

    private void stubProfile(IsfMealWindowDTO... windows) {
        when(profileService.getProfile(userId)).thenReturn(IsfMealWindowProfileResponse.builder()
                .windows(List.of(windows))
                .historyDays(14)
                .minWeightedSamples(7.0)
                .build());
    }

    private static IsfMealWindowDTO window(String name, Double isf, boolean hasData) {
        return IsfMealWindowDTO.builder()
                .mealWindow(name)
                .isfMmolPerU(isf)
                .hasData(hasData)
                .weightedSamples(hasData ? 8.0 : 0.0)
                .rawSampleCount(hasData ? 10 : 0)
                .build();
    }
}
