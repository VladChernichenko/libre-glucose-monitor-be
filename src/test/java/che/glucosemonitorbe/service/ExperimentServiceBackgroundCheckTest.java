package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.BackgroundStatusDTO;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
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
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ExperimentService#checkBackground(UUID)}.
 *
 * <p>Regression coverage for the bug where the old implementation applied
 * {@code carbHalfLife} to insulin (yielding 2.0 u IOB for a 5.5-hour-old bolus
 * instead of 0.0 u), causing false "Not Ready Yet" blocks in the Experiments tab.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExperimentServiceBackgroundCheckTest {

    @Mock private ExperimentRepository experimentRepository;
    @Mock private NoteRepository noteRepository;
    @Mock private CarbsOnBoardService cobService;
    @Mock private InsulinCalculatorService insulinCalculatorService;
    @Mock private COBSettingsService cobSettingsService;
    @Mock private UserInsulinPreferencesService userInsulinPreferencesService;

    private ExperimentService service;

    private final UUID userId = UUID.randomUUID();

    // Mirrors real user settings: carbHalfLife=180 min (the value that triggered the bug)
    private static final COBSettingsDTO SETTINGS_WITH_SLOW_CARB_HALFLIFE = settings(180);
    // Standard rapid insulin: Fiasp-like, DIA=5h, peak=55min
    private static final RapidInsulinIobParameters RAPID_IOB = new RapidInsulinIobParameters(5.0, 55.0);

    @BeforeEach
    void setUp() {
        service = new ExperimentService(
                experimentRepository, noteRepository,
                cobService, insulinCalculatorService,
                cobSettingsService, userInsulinPreferencesService);

        when(cobSettingsService.getCOBSettings(userId)).thenReturn(SETTINGS_WITH_SLOW_CARB_HALFLIFE);
        when(userInsulinPreferencesService.getRapidIobParameters(userId)).thenReturn(RAPID_IOB);
        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any()))
                .thenReturn(List.of());
    }

    // ── Clean states ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("No recent notes → isClean true, COB=0.0, IOB=0.0, cleanInMinutes=0")
    void noNotes_isClean() {
        stubClean();

        BackgroundStatusDTO result = service.checkBackground(userId);

        assertThat(result.isClean()).isTrue();
        assertThat(result.getCobGrams()).isZero();
        assertThat(result.getIobUnits()).isZero();
        assertThat(result.getCleanInMinutes()).isZero();
    }

    @Test
    @DisplayName("COB just below threshold → isClean true")
    void cobJustBelowThreshold_isClean() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(4.9);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        assertThat(service.checkBackground(userId).isClean()).isTrue();
    }

    @Test
    @DisplayName("IOB just below threshold → isClean true")
    void iobJustBelowThreshold_isClean() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.29);

        assertThat(service.checkBackground(userId).isClean()).isTrue();
    }

    // ── Dirty states ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Active carbs above threshold → isClean false, cobGrams reported")
    void highCOB_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(12.3);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        BackgroundStatusDTO result = service.checkBackground(userId);

        assertThat(result.isClean()).isFalse();
        assertThat(result.getCobGrams()).isEqualTo(12.3);
    }

    @Test
    @DisplayName("Active insulin above threshold → isClean false, iobUnits reported")
    void highIOB_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(1.5);

        BackgroundStatusDTO result = service.checkBackground(userId);

        assertThat(result.isClean()).isFalse();
        assertThat(result.getIobUnits()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("COB exactly at threshold (5.0 g) → isClean false (strict less-than)")
    void cobExactlyAtThreshold_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(5.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        assertThat(service.checkBackground(userId).isClean()).isFalse();
    }

    @Test
    @DisplayName("IOB exactly at threshold (0.3 u) → isClean false (strict less-than)")
    void iobExactlyAtThreshold_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.3);

        assertThat(service.checkBackground(userId).isClean()).isFalse();
    }

    @Test
    @DisplayName("Clean COB but dirty IOB → still dirty (both must be below threshold)")
    void cleanCobDirtyIob_isDirty() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(1.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.5);

        assertThat(service.checkBackground(userId).isClean()).isFalse();
    }

    // ── Regression: insulin must use DIA/peak, not carbHalfLife ─────────────

    @Test
    @DisplayName("REGRESSION — IOB delegated to InsulinCalculatorService with user's DIA and peak, not carbHalfLife")
    void iobUsesDiaAndPeak_notCarbHalfLife() {
        /*
         * Before the fix: ExperimentService applied carbHalfLife (180 min) to insulin:
         *   7.0 u × 0.5^(330/180) ≈ 2.0 u  → marked as dirty when user was actually clean.
         * After the fix: delegates to InsulinCalculatorService which uses the proper
         *   OpenAPS exponential IOB curve (DIA=5h, peak=55min) → 0.0 u after 5.5h.
         */
        stubClean();

        service.checkBackground(userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InsulinDose>> dosesCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Double> diaCaptor   = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> peakCaptor  = ArgumentCaptor.forClass(Double.class);

        verify(insulinCalculatorService).calculateTotalActiveInsulin(
                dosesCaptor.capture(), any(), diaCaptor.capture(), peakCaptor.capture());

        // Must use user's insulin parameters, not carbHalfLife
        assertThat(diaCaptor.getValue())
                .as("diaHours must come from UserInsulinPreferencesService (5.0), not carbHalfLife (180 min)")
                .isEqualTo(RAPID_IOB.diaHours());
        assertThat(peakCaptor.getValue())
                .as("peakMinutes must come from UserInsulinPreferencesService (55), not a half-life calculation")
                .isEqualTo(RAPID_IOB.peakMinutes());
    }

    @Test
    @DisplayName("REGRESSION — carbHalfLife setting is NOT used as insulin half-life")
    void carbHalfLifeValue_notPassedToInsulinCalculator() {
        // If the bug were still present, diaHours would end up as carbHalfLife/60 = 3.0 h
        stubClean();
        service.checkBackground(userId);

        ArgumentCaptor<Double> diaCaptor = ArgumentCaptor.forClass(Double.class);
        verify(insulinCalculatorService)
                .calculateTotalActiveInsulin(any(), any(), diaCaptor.capture(), anyDouble());

        double buggyDiaHours = SETTINGS_WITH_SLOW_CARB_HALFLIFE.getCarbHalfLife() / 60.0; // 3.0
        assertThat(diaCaptor.getValue())
                .as("diaHours must NOT equal carbHalfLife/60 — that would be the old bug")
                .isNotEqualTo(buggyDiaHours);
    }

    // ── Long-acting insulin exclusion ─────────────────────────────────────────

    @Test
    @DisplayName("Long-acting insulin note is excluded from IOB calculation")
    void longActingInsulin_notPassedToCalculator() {
        Note longActingNote = note(15.0, null, "long_acting");
        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any()))
                .thenReturn(List.of(longActingNote));
        stubClean();

        service.checkBackground(userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InsulinDose>> dosesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(insulinCalculatorService)
                .calculateTotalActiveInsulin(dosesCaptor.capture(), any(), anyDouble(), anyDouble());

        assertThat(dosesCaptor.getValue())
                .as("Long-acting insulin must be excluded from IOB input")
                .isEmpty();
    }

    @Test
    @DisplayName("Normal bolus note IS included in IOB calculation")
    void bolusInsulin_passedToCalculator() {
        Note bolusNote = note(3.0, null, "Correction");
        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any()))
                .thenReturn(List.of(bolusNote));
        stubClean();

        service.checkBackground(userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InsulinDose>> dosesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(insulinCalculatorService)
                .calculateTotalActiveInsulin(dosesCaptor.capture(), any(), anyDouble(), anyDouble());

        assertThat(dosesCaptor.getValue())
                .as("Bolus/correction insulin must be included in IOB")
                .hasSize(1);
    }

    // ── Repository window ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Repository is queried with an 8-hour lookback window (matches dashboard)")
    void repository_queriedWithEightHourWindow() {
        stubClean();

        LocalDateTime callTime = LocalDateTime.now();
        service.checkBackground(userId);

        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(noteRepository).findByUserIdAndTimestampBetween(
                eq(userId), sinceCaptor.capture(), any());

        LocalDateTime since = sinceCaptor.getValue();
        // Allow ±5 s for test execution overhead
        assertThat(since)
                .isBetween(callTime.minusHours(8).minusSeconds(5),
                           callTime.minusHours(8).plusSeconds(5));
    }

    // ── cleanInMinutes estimation ─────────────────────────────────────────────

    @Test
    @DisplayName("cleanInMinutes is 0 when state is already clean")
    void cleanState_cleanInMinutesIsZero() {
        stubClean();

        assertThat(service.checkBackground(userId).getCleanInMinutes()).isZero();
    }

    @Test
    @DisplayName("cleanInMinutes reflects first forward-sample minute that is clean")
    void cleanInMinutes_forwardSampledFromDecayCurves() {
        // Dirty now; clean at the first forward sample (+5 min)
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class)))
                .thenReturn(7.0)   // t=now  → dirty
                .thenReturn(2.0);  // t=+5m  → clean
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        BackgroundStatusDTO result = service.checkBackground(userId);

        assertThat(result.isClean()).isFalse();
        assertThat(result.getCleanInMinutes())
                .as("Should report the first 5-minute step at which curves drop below thresholds")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("cleanInMinutes caps at 600 when curves never reach threshold within window")
    void cleanInMinutes_capsAt600WhenNeverClean() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(99.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        BackgroundStatusDTO result = service.checkBackground(userId);

        assertThat(result.getCleanInMinutes())
                .as("cleanInMinutes must cap at 600 when curves never reach clean state")
                .isEqualTo(600);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubClean() {
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);
    }

    private Note note(double insulin, Double carbs, String type) {
        Note n = new Note();
        n.setId(UUID.randomUUID());
        n.setUserId(userId);
        n.setTimestamp(LocalDateTime.now().minusHours(1));
        n.setInsulin(insulin);
        n.setCarbs(carbs);
        n.setMeal("Test");
        if (type != null) n.setType(type);
        return n;
    }

    private static COBSettingsDTO settings(int carbHalfLife) {
        COBSettingsDTO s = new COBSettingsDTO();
        s.setIsf(2.0);
        s.setCarbRatio(0.2);
        s.setCarbHalfLife(carbHalfLife);
        s.setMaxCOBDuration(480);
        return s;
    }
}
