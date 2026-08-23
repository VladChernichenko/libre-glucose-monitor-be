package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextAggregatorServiceTest {

    @Mock CgmReadingRepository chartDataRepository;
    @Mock NoteRepository noteRepository;
    @Mock UserSettingsService userSettingsService;
    @Mock UserInsulinPreferencesService insulinPreferencesService;
    @Mock CarbsOnBoardService carbsOnBoardService;
    @Mock InsulinCalculatorService insulinCalculatorService;

    @InjectMocks ContextAggregatorService service;

    private UUID userId;
    private UserSettingsDTO defaultUserSettings;
    private UserInsulinPreferencesDTO defaultInsulinPrefs;
    private RapidInsulinIobParameters defaultRapidIob;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        defaultUserSettings = new UserSettingsDTO();
        defaultUserSettings.setCarbRatio(2.0);
        defaultUserSettings.setIsf(2.5);
        defaultInsulinPrefs = new UserInsulinPreferencesDTO();
        defaultRapidIob = new RapidInsulinIobParameters(4.0, 75);

        when(userSettingsService.getUserSettings(userId)).thenReturn(defaultUserSettings);
        when(insulinPreferencesService.getPreferences(userId)).thenReturn(defaultInsulinPrefs);
        when(insulinPreferencesService.getRapidIobParameters(userId)).thenReturn(defaultRapidIob);
        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(List.of());
        when(carbsOnBoardService.calculateTotalCarbsOnBoard(any(), any(), eq(userId))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble())).thenReturn(0.0);
    }

    @Test
    @DisplayName("buildContext converts sgv to mmol/L (÷18) and populates statistics")
    void buildContext_convertsSgvToMmolL() {
        // sgv 108 -> 6.0 mmol/L, sgv 162 -> 9.0 mmol/L
        long now = System.currentTimeMillis();
        CgmReading r1 = chartRow(108, now - 3_600_000L);
        CgmReading r2 = chartRow(162, now - 60_000L);
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId)).thenReturn(List.of(r1, r2));

        AnalysisContext ctx = service.buildContext(userId, 12);

        assertThat(ctx.getLatestGlucose()).isCloseTo(9.0, within(0.1));
        assertThat(ctx.getMinGlucose()).isCloseTo(6.0, within(0.1));
        assertThat(ctx.getMaxGlucose()).isCloseTo(9.0, within(0.1));
        assertThat(ctx.getDeltaGlucose()).isCloseTo(3.0, within(0.1));
        assertThat(ctx.getGlucoseValues()).hasSize(2);
    }

    @Test
    @DisplayName("buildContext with no glucose readings returns zero stats")
    void buildContext_noReadings_zeroStats() {
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId)).thenReturn(List.of());

        AnalysisContext ctx = service.buildContext(userId, 12);

        assertThat(ctx.getLatestGlucose()).isZero();
        assertThat(ctx.getMinGlucose()).isZero();
        assertThat(ctx.getGlucoseValues()).isEmpty();
    }

    @Test
    @DisplayName("2h prediction clamped to [1, 25] with activeCOB contribution")
    void buildContext_2hPredictionClamped() {
        long now = System.currentTimeMillis();
        CgmReading r = chartRow(450, now - 1000); // 25 mmol/L
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId)).thenReturn(List.of(r));
        when(carbsOnBoardService.calculateTotalCarbsOnBoard(any(), any(), eq(userId))).thenReturn(200.0); // huge COB

        AnalysisContext ctx = service.buildContext(userId, 12);

        assertThat(ctx.getPredictedGlucose2h()).isLessThanOrEqualTo(25.0);
        assertThat(ctx.getPredictedGlucose2h()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("correction units are positive when latest glucose exceeds 6.5 and IOB is zero")
    void buildContext_correctionUnitsCalculatedWhenHyper() {
        long now = System.currentTimeMillis();
        CgmReading r = chartRow((int)(11.5 * 18), now - 1000);
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId)).thenReturn(List.of(r));
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble())).thenReturn(0.0);

        AnalysisContext ctx = service.buildContext(userId, 12);

        // (11.5 - 6.5) / 2.5 - 0 = 2.0
        assertThat(ctx.getEstimatedCorrectionUnits()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("pre-bolus pause computed when bolus note precedes meal note within 90 min")
    void buildContext_preBolusStatistics() {
        UUID uid = userId;
        LocalDateTime mealTime = LocalDateTime.now().minusMinutes(30);
        LocalDateTime bolusTime = mealTime.minusMinutes(15);

        Note bolusNote = new Note();
        bolusNote.setId(UUID.randomUUID());
        bolusNote.setUserId(uid);
        bolusNote.setTimestamp(bolusTime);
        bolusNote.setInsulin(4.0);
        bolusNote.setCarbs(0.0);

        Note mealNote = new Note();
        mealNote.setId(UUID.randomUUID());
        mealNote.setUserId(uid);
        mealNote.setTimestamp(mealTime);
        mealNote.setCarbs(50.0);
        mealNote.setInsulin(0.0);

        when(noteRepository.findByUserIdAndTimestampBetween(eq(uid), any(), any()))
                .thenReturn(List.of(bolusNote, mealNote));
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(uid)).thenReturn(List.of());

        AnalysisContext ctx = service.buildContext(uid, 12);

        assertThat(ctx.getAvgPreBolusPauseMinutes()).isNotNull();
        assertThat(ctx.getAvgPreBolusPauseMinutes()).isCloseTo(15.0, within(1.0));
    }

    @Test
    @DisplayName("pre-bolus timing contribution is positive when avg pause < 10 min (late bolus)")
    void buildContext_preBolusTimingContributionPositiveForLateBolus() {
        UUID uid = userId;
        LocalDateTime mealTime = LocalDateTime.now().minusMinutes(10);
        LocalDateTime bolusTime = mealTime.minusMinutes(5); // only 5 min before meal

        Note bolusNote = new Note();
        bolusNote.setId(UUID.randomUUID());
        bolusNote.setUserId(uid);
        bolusNote.setTimestamp(bolusTime);
        bolusNote.setInsulin(3.0);
        bolusNote.setCarbs(0.0);

        Note mealNote = new Note();
        mealNote.setId(UUID.randomUUID());
        mealNote.setUserId(uid);
        mealNote.setTimestamp(mealTime);
        mealNote.setCarbs(40.0);
        mealNote.setInsulin(0.0);

        when(noteRepository.findByUserIdAndTimestampBetween(eq(uid), any(), any()))
                .thenReturn(List.of(bolusNote, mealNote));
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(uid)).thenReturn(List.of());

        AnalysisContext ctx = service.buildContext(uid, 12);

        assertThat(ctx.getPreBolusTimingContribution()).isGreaterThan(0.0);
    }

    // ---- C2: correction estimate must track the meal-window ISF ----

    /**
     * {@code SafetyAndScoringService} renders {@code estimatedCorrectionUnits} to the patient as a
     * "Correction guidance estimate". Before this fix that field read {@code settings.getIsf()} - the
     * single autotuned base value - even though {@code InsulinCalculatorService}'s correction leg had
     * already been made meal-window-aware. That let this card show a different, contradicting unit
     * figure than the actual dosing calculator for the same glucose/IOB at the same moment.
     *
     * <p>Same glucose (11.5 mmol/L) and IOB (0u), two different times of day with different manual
     * ISF overrides, must produce two different suggested unit figures - proving the estimate is
     * resolved per window rather than pinned to the base ISF.
     */
    @Test
    @DisplayName("C2: estimated correction units use the meal-window ISF, not the base ISF")
    void buildContext_correctionUnitsTrackMealWindowIsf() {
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setCarbRatio(2.0);
        settings.setIsf(2.5);          // base/autotuned ISF - must NOT be used when a window override exists
        settings.setIsfBreakfast(2.0); // 05:00-10:59
        settings.setIsfDinner(4.0);    // 16:00-21:59
        when(userSettingsService.getUserSettings(userId)).thenReturn(settings);

        LocalDateTime breakfastNow = LocalDateTime.of(2026, 8, 23, 8, 0);
        LocalDateTime dinnerNow = LocalDateTime.of(2026, 8, 23, 19, 0);

        CgmReading breakfastReading = chartRowAt(11.5, breakfastNow.minusMinutes(1));
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId))
                .thenReturn(List.of(breakfastReading));
        AnalysisContext breakfastCtx = service.buildContext(userId, 12, breakfastNow);

        CgmReading dinnerReading = chartRowAt(11.5, dinnerNow.minusMinutes(1));
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId))
                .thenReturn(List.of(dinnerReading));
        AnalysisContext dinnerCtx = service.buildContext(userId, 12, dinnerNow);

        // (11.5 - 6.5) / 2.0 - 0 = 2.5u at the breakfast-window ISF
        assertThat(breakfastCtx.getEstimatedCorrectionUnits()).isCloseTo(2.5, within(0.01));
        // (11.5 - 6.5) / 4.0 - 0 = 1.25u at the dinner-window ISF
        assertThat(dinnerCtx.getEstimatedCorrectionUnits()).isCloseTo(1.25, within(0.01));
        assertThat(breakfastCtx.getEstimatedCorrectionUnits())
                .as("same glucose and IOB must still yield different guidance across meal windows")
                .isNotEqualTo(dinnerCtx.getEstimatedCorrectionUnits());
    }

    // ---- rescue-carb marker ----

    /**
     * The advisor's COB must see a rescue carb as a rescue. This aggregator hand-builds its own
     * {@code CarbsEntry} rather than routing through {@code NoteToCarbsEntryMapper}, and once did so
     * without an {@code absorptionMode} at all - so {@code RescueCarbProfile.isRescue(null)} was
     * false, and the advisor was told a 15 g rescue still had ~11 g on board 30 minutes later while
     * the dashboard, which does route through the mapper, reported it three-quarters absorbed.
     */
    @Test
    @DisplayName("a hypo_treatment note reaches the COB calculation carrying the rescue marker")
    void buildContext_marksHypoTreatmentNotesAsRescue() {
        Note rescue = new Note();
        rescue.setTimestamp(LocalDateTime.now().minusMinutes(30));
        rescue.setCarbs(15.0);
        rescue.setInsulin(0.0);
        rescue.setType(Note.TYPE_HYPO_TREATMENT);

        Note meal = new Note();
        meal.setTimestamp(LocalDateTime.now().minusMinutes(30));
        meal.setCarbs(40.0);
        meal.setInsulin(0.0);

        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any()))
                .thenReturn(List.of(rescue, meal));
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId)).thenReturn(List.of());

        service.buildContext(userId, 12);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(carbsOnBoardService).calculateTotalCarbsOnBoard(captor.capture(), any(), eq(userId));
        List<CarbsEntry> entries = captor.getValue();

        assertThat(entries).hasSize(2);
        CarbsEntry rescueEntry = entries.stream()
                .filter(e -> e.getCarbs() == 15.0).findFirst().orElseThrow();
        CarbsEntry mealEntry = entries.stream()
                .filter(e -> e.getCarbs() == 40.0).findFirst().orElseThrow();

        assertThat(RescueCarbProfile.isRescue(rescueEntry.getAbsorptionMode()))
                .as("absorptionMode was %s", rescueEntry.getAbsorptionMode())
                .isTrue();
        assertThat(RescueCarbProfile.isRescue(mealEntry.getAbsorptionMode()))
                .as("an ordinary meal must not be marked - otherwise this passes for a mutant "
                    + "that marks everything")
                .isFalse();
    }

    // ---- helpers ----

    private CgmReading chartRow(int sgv, long tsMs) {
        CgmReading row = new CgmReading();
        row.setSgv(sgv);
        row.setDateTimestamp(tsMs);
        row.setUserId(userId);
        return row;
    }

    private CgmReading chartRowAt(double mmol, LocalDateTime timestamp) {
        return chartRow((int) Math.round(mmol * 18.0), timestamp.toInstant(ZoneOffset.UTC).toEpochMilli());
    }
}
