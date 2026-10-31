package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.DigitalTwinService;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real {@link GlucoseCalculationsService} on the real Hovorka ODE for one user with no logged
 * notes (repositories and settings mocked). Settings: ISF 2.5, carb ratio 1.5 mmol/L per 10 g,
 * carb half-life 45 min, 70 kg, Fiasp-like IOB (4.5 h, peak 55 min).
 */
final class RealDashboardForecast {

    /** Calculator dose for 60 g at carb ratio 1.5 / ISF 2.5: 60 / 10 × 1.5 / 2.5. */
    static final double DOSE_FOR_60G = 3.6;

    private final GlucoseCalculationsService service;
    private final NoteRepository notes = mock(NoteRepository.class);
    private final UserService users = mock(UserService.class);

    RealDashboardForecast(UUID userId, String username) {
        UserSettingsService settingsService = mock(UserSettingsService.class);
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setUserId(userId);
        settings.setIsf(2.5);
        settings.setCarbRatio(1.5);
        settings.setCarbHalfLife(45);
        settings.setMaxCOBDuration(240);
        settings.setBodyWeightKg(70.0);
        when(settingsService.getUserSettings(userId)).thenReturn(settings);

        UserInsulinPreferencesService prefs = mock(UserInsulinPreferencesService.class);
        when(prefs.getRapidIobParameters(userId)).thenReturn(new RapidInsulinIobParameters(4.5, 55.0));
        when(prefs.getLongActingDurationHours(userId)).thenReturn(BasalInsulinResolver.DEFAULT_DURATION_HOURS);

        FeatureToggleConfig toggles = mock(FeatureToggleConfig.class);
        when(toggles.isHovorkaModelEnabled()).thenReturn(true);
        when(toggles.isNutritionAwarePredictionEnabled()).thenReturn(true);

        when(notes.findByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(List.of());
        when(users.getUserByUsername(username)).thenReturn(UserDto.builder().id(userId).username(username).build());
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        when(users.getUserById(userId)).thenReturn(user);

        DallaManGutModel gut = new DallaManGutModel();
        HovorkaGlucosePredictionService hovorka = new HovorkaGlucosePredictionService(
                new HovorkaParameterService(settingsService, mock(DigitalTwinService.class)),
                new HovorkaOdeSolver(gut), new BasalInsulinResolver(), prefs, gut,
                settingsService, PredictionResidualProvider.NONE);

        service = new GlucoseCalculationsService(
                mock(CarbsOnBoardService.class), mock(InsulinCalculatorService.class), notes, users, prefs,
                new ObjectMapper(), toggles, settingsService, mock(NoteToCarbsEntryMapper.class));
        ReflectionTestUtils.setField(service, "hovorkaService", hovorka);
    }

    GlucoseCalculationsService service() { return service; }
    NoteRepository notes() { return notes; }
    UserService users() { return users; }
}
