package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.BackgroundStatusDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.repository.ExperimentRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExperimentService#checkBackground(UUID, String)}.
 *
 * <p>checkBackground delegates COB/IOB INPUTS (note fetch + nutrition-aware conversion +
 * long-acting exclusion) to {@link GlucoseCalculationsService#activeCobIobInputs}, the same
 * single source of truth the dashboard headline uses. That delegation is what guarantees the
 * Experiments tab and the dashboard can never show different COB/IOB. These tests therefore
 * cover what is genuinely ExperimentService's own responsibility: the clean/dirty threshold
 * decision, the cleanInMinutes forward-sampling, and that IOB is computed with the user's
 * DIA/peak (regression for the old carbHalfLife-as-DIA bug). Conversion/window/long-acting
 * coverage lives with the shared method in {@code GlucoseCalculationsServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExperimentServiceBackgroundCheckTest {

    @Mock private ExperimentRepository experimentRepository;
    @Mock private NoteRepository noteRepository;
    @Mock private CarbsOnBoardService cobService;
    @Mock private InsulinCalculatorService insulinCalculatorService;
    @Mock private UserSettingsService userSettingsService;
    @Mock private GlucoseCalculationsService calculationsService;

    private ExperimentService service;

    private final UUID userId = UUID.randomUUID();

    // Mirrors real user settings: carbHalfLife=180 min (the value that triggered the old bug)
    private static final UserSettingsDTO SETTINGS_WITH_SLOW_CARB_HALFLIFE = settings(180);
    // Standard rapid insulin: Fiasp-like, DIA=5h, peak=55min
    private static final RapidInsulinIobParameters RAPID_IOB = new RapidInsulinIobParameters(5.0, 55.0);

    @BeforeEach
    void setUp() {
        service = new ExperimentService(
                experimentRepository, noteRepository,
                cobService, insulinCalculatorService,
                userSettingsService, calculationsService);

        // Default: no active entries. The settings + rapid-IOB parameters are carried by the
        // shared inputs object, exactly as they reach the dashboard.
        when(calculationsService.activeCobIobInputs(eq(userId), any()))
                .thenReturn(inputs(List.of(), List.of()));
    }

    // -- Clean states ----------------------------------------------------------

    @Test
    @DisplayName("No active entries -> isClean true, COB=0.0, IOB=0.0, cleanInMinutes=0")
    void noNotes_isClean() {
        stubClean();

        BackgroundStatusDTO result = service.checkBackground(userId, null);

        assertThat(result.isClean()).isTrue();
        assertThat(result.getCobGrams()).isZero();
        assertThat(result.getIobUnits()).isZero();
        assertThat(result.getCleanInMinutes()).isZero();
    }

    @Test
    @DisplayName("COB just below threshold -> isClean true")
    void cobJustBelowThreshold_isClean() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class))).thenReturn(4.9);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        assertThat(service.checkBackground(userId, null).isClean()).isTrue();
    }

    @Test
    @DisplayName("IOB just below threshold -> isClean true")
    void iobJustBelowThreshold_isClean() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.29);

        assertThat(service.checkBackground(userId, null).isClean()).isTrue();
    }

    // -- Dirty states ----------------------------------------------------------

    @Test
    @DisplayName("Active carbs above threshold -> isClean false, cobGrams reported")
    void highCOB_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class))).thenReturn(12.3);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        BackgroundStatusDTO result = service.checkBackground(userId, null);

        assertThat(result.isClean()).isFalse();
        assertThat(result.getCobGrams()).isEqualTo(12.3);
    }

    @Test
    @DisplayName("Active insulin above threshold -> isClean false, iobUnits reported")
    void highIOB_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(1.5);

        BackgroundStatusDTO result = service.checkBackground(userId, null);

        assertThat(result.isClean()).isFalse();
        assertThat(result.getIobUnits()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("COB exactly at threshold (5.0 g) -> isClean false (strict less-than)")
    void cobExactlyAtThreshold_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class))).thenReturn(5.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        assertThat(service.checkBackground(userId, null).isClean()).isFalse();
    }

    @Test
    @DisplayName("IOB exactly at threshold (0.3 u) -> isClean false (strict less-than)")
    void iobExactlyAtThreshold_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.3);

        assertThat(service.checkBackground(userId, null).isClean()).isFalse();
    }

    @Test
    @DisplayName("Clean COB but dirty IOB -> still dirty (both must be below threshold)")
    void cleanCobDirtyIob_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class))).thenReturn(1.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.5);

        assertThat(service.checkBackground(userId, null).isClean()).isFalse();
    }

    // -- Regression: insulin must use DIA/peak, not carbHalfLife -------------

    @Test
    @DisplayName("REGRESSION - IOB delegated to InsulinCalculatorService with user's DIA and peak, not carbHalfLife")
    void iobUsesDiaAndPeak_notCarbHalfLife() {
        /*
         * Before the fix: ExperimentService applied carbHalfLife (180 min) to insulin:
         *   7.0 u × 0.5^(330/180) ≈ 2.0 u  -> marked as dirty when user was actually clean.
         * After the fix: delegates to InsulinCalculatorService which uses the proper
         *   OpenAPS exponential IOB curve (DIA=5h, peak=55min) -> 0.0 u after 5.5h.
         * The DIA/peak now arrive via the shared inputs (inputs.rapidIob()).
         */
        stubClean();

        service.checkBackground(userId, null);

        ArgumentCaptor<Double> diaCaptor   = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> peakCaptor  = ArgumentCaptor.forClass(Double.class);

        verify(insulinCalculatorService).calculateTotalActiveInsulin(
                any(), any(), diaCaptor.capture(), peakCaptor.capture());

        // Must use the user's insulin parameters, not carbHalfLife
        assertThat(diaCaptor.getValue())
                .as("diaHours must come from the shared inputs' RapidInsulinIobParameters (5.0), not carbHalfLife (180 min)")
                .isEqualTo(RAPID_IOB.diaHours());
        assertThat(peakCaptor.getValue())
                .as("peakMinutes must come from RapidInsulinIobParameters (55), not a half-life calculation")
                .isEqualTo(RAPID_IOB.peakMinutes());
    }

    @Test
    @DisplayName("REGRESSION - carbHalfLife setting is NOT used as insulin half-life")
    void carbHalfLifeValue_notPassedToInsulinCalculator() {
        // If the bug were still present, diaHours would end up as carbHalfLife/60 = 3.0 h
        stubClean();
        service.checkBackground(userId, null);

        ArgumentCaptor<Double> diaCaptor = ArgumentCaptor.forClass(Double.class);
        verify(insulinCalculatorService)
                .calculateTotalActiveInsulin(any(), any(), diaCaptor.capture(), anyDouble());

        double buggyDiaHours = SETTINGS_WITH_SLOW_CARB_HALFLIFE.getCarbHalfLife() / 60.0; // 3.0
        assertThat(diaCaptor.getValue())
                .as("diaHours must NOT equal carbHalfLife/60 - that would be the old bug")
                .isNotEqualTo(buggyDiaHours);
    }

    // -- Shared-source delegation ----------------------------------------------

    @Test
    @DisplayName("COB/IOB inputs come from the shared GlucoseCalculationsService (same source as the dashboard)")
    void delegatesToSharedCobIobInputs() {
        stubClean();

        service.checkBackground(userId, null);

        // The Experiments background check must read its COB/IOB inputs from the exact same
        // method the dashboard headline uses - that is the whole point of the fix.
        verify(calculationsService).activeCobIobInputs(eq(userId), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("The carb/insulin entries from the shared inputs are the ones scored for COB/IOB")
    void scoresEntriesFromSharedInputs() {
        CarbsEntry carb = CarbsEntry.builder().id(UUID.randomUUID()).userId(userId)
                .timestamp(LocalDateTime.now().minusMinutes(30)).carbs(40.0).build();
        InsulinDose dose = InsulinDose.builder().id(UUID.randomUUID()).userId(userId)
                .timestamp(LocalDateTime.now().minusMinutes(30)).units(3.0)
                .type(InsulinDose.InsulinType.BOLUS).build();
        when(calculationsService.activeCobIobInputs(eq(userId), any()))
                .thenReturn(inputs(List.of(carb), List.of(dose)));
        stubClean();

        service.checkBackground(userId, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbCaptor = ArgumentCaptor.forClass(List.class);
        verify(cobService).calculateTotalCarbsOnBoard(carbCaptor.capture(), any(), any(UserSettingsDTO.class));
        assertThat(carbCaptor.getValue()).containsExactly(carb);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InsulinDose>> doseCaptor = ArgumentCaptor.forClass(List.class);
        verify(insulinCalculatorService)
                .calculateTotalActiveInsulin(doseCaptor.capture(), any(), anyDouble(), anyDouble());
        assertThat(doseCaptor.getValue()).containsExactly(dose);
    }

    // -- cleanInMinutes estimation ---------------------------------------------

    @Test
    @DisplayName("cleanInMinutes is 0 when state is already clean")
    void cleanState_cleanInMinutesIsZero() {
        stubClean();

        assertThat(service.checkBackground(userId, null).getCleanInMinutes()).isZero();
    }

    @Test
    @DisplayName("cleanInMinutes reflects first forward-sample minute that is clean")
    void cleanInMinutes_forwardSampledFromDecayCurves() {
        // Dirty now; clean at the first forward sample (+5 min)
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class)))
                .thenReturn(7.0)   // t=now  -> dirty
                .thenReturn(2.0);  // t=+5m  -> clean
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        BackgroundStatusDTO result = service.checkBackground(userId, null);

        assertThat(result.isClean()).isFalse();
        assertThat(result.getCleanInMinutes())
                .as("Should report the first 5-minute step at which curves drop below thresholds")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("cleanInMinutes caps at 600 when curves never reach threshold within window")
    void cleanInMinutes_capsAt600WhenNeverClean() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class))).thenReturn(99.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        BackgroundStatusDTO result = service.checkBackground(userId, null);

        assertThat(result.getCleanInMinutes())
                .as("cleanInMinutes must cap at 600 when curves never reach clean state")
                .isEqualTo(600);
    }

    // -- helpers ---------------------------------------------------------------

    private void stubClean() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);
    }

    private GlucoseCalculationsService.ActiveCobIobInputs inputs(List<CarbsEntry> carbs, List<InsulinDose> insulin) {
        return new GlucoseCalculationsService.ActiveCobIobInputs(
                List.of(), carbs, insulin, SETTINGS_WITH_SLOW_CARB_HALFLIFE, RAPID_IOB);
    }

    private static UserSettingsDTO settings(int carbHalfLife) {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setIsf(2.0);
        s.setCarbRatio(0.2);
        s.setCarbHalfLife(carbHalfLife);
        s.setMaxCOBDuration(480);
        return s;
    }
}
