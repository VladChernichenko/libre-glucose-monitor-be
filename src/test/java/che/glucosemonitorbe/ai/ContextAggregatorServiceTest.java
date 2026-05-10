package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.NightscoutChartData;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NightscoutChartDataRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.COBSettingsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextAggregatorServiceTest {

    @Mock NightscoutChartDataRepository chartDataRepository;
    @Mock NoteRepository noteRepository;
    @Mock COBSettingsService cobSettingsService;
    @Mock UserInsulinPreferencesService insulinPreferencesService;
    @Mock CarbsOnBoardService carbsOnBoardService;
    @Mock InsulinCalculatorService insulinCalculatorService;

    @InjectMocks ContextAggregatorService service;

    private UUID userId;
    private COBSettingsDTO defaultCobSettings;
    private UserInsulinPreferencesDTO defaultInsulinPrefs;
    private RapidInsulinIobParameters defaultRapidIob;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        defaultCobSettings = new COBSettingsDTO();
        defaultCobSettings.setCarbRatio(2.0);
        defaultCobSettings.setIsf(2.5);
        defaultInsulinPrefs = new UserInsulinPreferencesDTO();
        defaultRapidIob = new RapidInsulinIobParameters(4.0, 75);

        when(cobSettingsService.getCOBSettings(userId)).thenReturn(defaultCobSettings);
        when(insulinPreferencesService.getPreferences(userId)).thenReturn(defaultInsulinPrefs);
        when(insulinPreferencesService.getRapidIobParameters(userId)).thenReturn(defaultRapidIob);
        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(List.of());
        when(carbsOnBoardService.calculateTotalCarbsOnBoard(any(), any(), eq(userId))).thenReturn(0.0);
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble())).thenReturn(0.0);
    }

    @Test
    @DisplayName("buildContext converts sgv to mmol/L (÷18) and populates statistics")
    void buildContext_convertsSgvToMmolL() {
        // sgv 108 → 6.0 mmol/L, sgv 162 → 9.0 mmol/L
        long now = System.currentTimeMillis();
        NightscoutChartData r1 = chartRow(108, now - 3_600_000L);
        NightscoutChartData r2 = chartRow(162, now - 60_000L);
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
        NightscoutChartData r = chartRow(450, now - 1000); // 25 mmol/L
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
        NightscoutChartData r = chartRow((int)(11.5 * 18), now - 1000);
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

    // ---- helpers ----

    private NightscoutChartData chartRow(int sgv, long tsMs) {
        NightscoutChartData row = new NightscoutChartData();
        row.setSgv(sgv);
        row.setDateTimestamp(tsMs);
        row.setUserId(userId);
        return row;
    }
}
