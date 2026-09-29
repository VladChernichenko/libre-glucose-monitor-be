package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.ActivityProvider;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.ProteinGluconeogenesis;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The dashboard must hand the Hovorka model everything a meal does to glucose - including notes
 * without carbs (a salad / eggs first course) and the delayed protein glucose - while the headline
 * COB the user reads stays the plain carbs they logged.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardMealMacrosWiringTest {

    @Mock private CarbsOnBoardService cobService;
    @Mock private InsulinCalculatorService insulinCalculatorService;
    @Mock private NoteRepository noteRepository;
    @Mock private UserService userService;
    @Mock private UserInsulinPreferencesService userInsulinPreferencesService;
    @Mock private ObjectMapper objectMapper;
    @Mock private FeatureToggleConfig featureToggleConfig;
    @Mock private UserSettingsService userSettingsService;
    @Mock private NoteToCarbsEntryMapper noteToCarbsEntryMapper;
    @Mock private HovorkaGlucosePredictionService hovorkaService;

    private GlucoseCalculationsService service;
    private final Map<UUID, CarbsEntry> entriesByNote = new HashMap<>();
    private final UUID userId = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.of(2024, 6, 1, 19, 0);

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(inv -> entriesByNote.get(((Note) inv.getArgument(0)).getId()))
                .when(noteToCarbsEntryMapper).toCarbsEntry(any(Note.class));

        service = new GlucoseCalculationsService(
                cobService, insulinCalculatorService, noteRepository,
                userService, userInsulinPreferencesService, objectMapper,
                featureToggleConfig, userSettingsService, noteToCarbsEntryMapper);
        Field f = GlucoseCalculationsService.class.getDeclaredField("hovorkaService");
        f.setAccessible(true);
        f.set(service, hovorkaService);

        when(userService.getUserByUsername("u")).thenReturn(UserDto.builder().id(userId).username("u").build());
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setUserId(userId);
        settings.setCarbRatio(1.5);
        settings.setIsf(2.5);
        settings.setCarbHalfLife(45);
        settings.setMaxCOBDuration(240);
        when(userSettingsService.getUserSettings(userId)).thenReturn(settings);
        when(userInsulinPreferencesService.getRapidIobParameters(userId))
                .thenReturn(new RapidInsulinIobParameters(4.5, 55.0));
        when(featureToggleConfig.isHovorkaModelEnabled()).thenReturn(true);
        when(hovorkaService.buildPredictionPath(anyDouble(), any(LocalDateTime.class), anyList(), anyList(),
                anyList(), any(UUID.class), anyInt(), any(ActivityProvider.class)))
                .thenReturn(List.of(PredictionPointDTO.builder()
                        .timestamp(now.plusMinutes(5)).predictedGlucose(7.0).build()));
    }

    @Test
    @DisplayName("salad first course and delayed protein glucose reach the model, not the COB")
    void firstCourseAndProteinGlucose_reachTheModelButNotTheHeadlineCob() {
        Note salad = note(now.minusMinutes(10), 0.0, 5.0, 6.0);
        Note steak = note(now, 40.0, 4.0, 60.0);
        when(noteRepository.findByUserIdAndTimestampBetween(any(UUID.class), any(), any()))
                .thenReturn(List.of(salad, steak));

        service.calculateGlucoseData(GlucoseCalculationsRequest.builder()
                .currentGlucose(8.0).userId("u").build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> toModel = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService).buildPredictionPath(anyDouble(), any(LocalDateTime.class), toModel.capture(),
                anyList(), anyList(), any(UUID.class), anyInt(), any(ActivityProvider.class));
        List<CarbsEntry> modelled = toModel.getValue();

        assertThat(modelled)
                .as("the carb-free salad must reach the model - its fiber slows the steak's carbs")
                .contains(entriesByNote.get(salad.getId()));
        assertThat(modelled)
                .as("the 60 g of protein must add delayed glucose at meal + onset")
                .anySatisfy(e -> {
                    assertThat(e.getMealType()).isEqualTo(ProteinGluconeogenesis.MEAL_TYPE);
                    assertThat(e.getTimestamp()).isEqualTo(now.plusMinutes(ProteinGluconeogenesis.ONSET_MIN));
                });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> toCob = ArgumentCaptor.forClass(List.class);
        verify(cobService, atLeastOnce()).calculateTotalCarbsOnBoard(
                toCob.capture(), any(LocalDateTime.class), any(UserSettingsDTO.class));
        assertThat(toCob.getAllValues())
                .as("headline COB = the carbs the user logged: no salad, no protein equivalent")
                .allSatisfy(list -> assertThat(list).containsExactly(entriesByNote.get(steak.getId())));
    }

    private Note note(LocalDateTime when, double carbs, double fiber, double protein) {
        Note n = new Note();
        n.setId(UUID.randomUUID());
        n.setUserId(userId);
        n.setTimestamp(when);
        n.setCarbs(carbs);
        n.setMeal("Test");
        n.setNutritionProfile("{\"fiber\":" + fiber + ",\"protein\":" + protein + "}");
        CarbsEntry e = CarbsEntry.builder().id(n.getId()).timestamp(when).carbs(carbs)
                .originalCarbs(carbs).userId(userId).build();
        e.setFiber(fiber);
        e.setProtein(protein);
        entriesByNote.put(n.getId(), e);
        return n;
    }
}
