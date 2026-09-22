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
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
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
    @Mock GlucoseCalculationsService calculationsService;

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
        // Default: the dose calculator's own behaviour is covered by InsulinCalculatorServiceTest
        // and InsulinCalculatorServiceIsfWindowTest; here it only has to return something.
        when(insulinCalculatorService.calculateRecommendedInsulin(any()))
                .thenReturn(InsulinCalculationResponse.builder().recommendedInsulin(0.0).build());
        // Default: canonical COB/IOB inputs. Individual tests override with their own entries.
        when(calculationsService.activeCobIobInputs(eq(userId), any()))
                .thenReturn(new GlucoseCalculationsService.ActiveCobIobInputs(
                        List.of(), List.of(), List.of(), defaultUserSettings, defaultRapidIob));
        when(carbsOnBoardService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class)))
                .thenReturn(0.0);
        // Default: the canonical prediction path is exercised in its own tests; here it only has
        // to return something well-formed so unrelated assertions are not testing an NPE.
        when(calculationsService.calculateGlucoseData(any()))
                .thenReturn(GlucoseCalculationsResponse.builder()
                        .twoHourPrediction(6.0)
                        .activeCarbsOnBoard(0.0)
                        .activeInsulinOnBoard(0.0)
                        .build());
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
    @DisplayName("#22: with no CGM readings there is no forecast to report, but COB/IOB still resolve")
    void buildContext_noReadings_reportsNoForecastRatherThanOne() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 12, 0);
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId)).thenReturn(List.of());
        when(carbsOnBoardService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class)))
                .thenReturn(18.0);

        AnalysisContext ctx = service.buildContext(userId, 12, now);

        // latest defaults to 0.0 with no readings; forecasting from it would put a fabricated
        // number in front of the patient and into the LLM prompt.
        assertThat(ctx.getPredictedGlucose2h())
                .as("no reading means no forecast, not a forecast from 0.0 mmol/L").isNull();
        assertThat(ctx.getEstimatedCorrectionUnits())
                .as("no reading means no correction guidance").isNull();
        assertThat(ctx.getActiveCob())
                .as("COB does not depend on a CGM reading and must still be reported").isEqualTo(18.0);
    }

    @Test
    @DisplayName("#22: the correction estimate comes from the canonical dose calculator")
    void buildContext_correctionDelegatesToInsulinCalculator() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 12, 0);
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId))
                .thenReturn(List.of(chartRowAt(11.5, now.minusMinutes(1))));
        // Local formula would give (11.5 - 6.5) / 2.5 = 2.0u. The canonical calculator says 1.75.
        when(insulinCalculatorService.calculateRecommendedInsulin(any()))
                .thenReturn(InsulinCalculationResponse.builder().recommendedInsulin(1.75).build());

        AnalysisContext ctx = service.buildContext(userId, 12, now);

        assertThat(ctx.getEstimatedCorrectionUnits())
                .as("the dose shown at priority high must be the canonical calculator's, not a local formula")
                .isEqualTo(1.75);
    }

    @Test
    @DisplayName("#22: a dosing refusal yields no correction guidance rather than failing the analysis")
    void buildContext_dosingRefusalSuppressesGuidance() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 12, 0);
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId))
                .thenReturn(List.of(chartRowAt(22.0, now.minusMinutes(1))));
        when(insulinCalculatorService.calculateRecommendedInsulin(any()))
                .thenThrow(new DosingRefusedException(DosingRefusalReason.DOSE_EXCEEDS_MAX_BOLUS, "too big"));

        AnalysisContext ctx = service.buildContext(userId, 12, now);

        assertThat(ctx.getEstimatedCorrectionUnits())
                .as("a refused dose must not be rendered, and must not break the whole analysis")
                .isNull();
        assertThat(ctx.getLatestGlucose()).isEqualTo(22.0);
    }

    @Test
    @DisplayName("#22: the dose request is pinned to the analysis instant so the meal-window ISF resolves there")
    void buildContext_doseRequestCarriesAnalysisInstant() {
        LocalDateTime dinner = LocalDateTime.of(2026, 8, 23, 19, 0);
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId))
                .thenReturn(List.of(chartRowAt(11.5, dinner.minusMinutes(1))));
        when(insulinCalculatorService.calculateRecommendedInsulin(any()))
                .thenReturn(InsulinCalculationResponse.builder().recommendedInsulin(1.0).build());
        ArgumentCaptor<InsulinCalculationRequest> captor =
                ArgumentCaptor.forClass(InsulinCalculationRequest.class);

        service.buildContext(userId, 12, dinner);

        verify(insulinCalculatorService).calculateRecommendedInsulin(captor.capture());
        InsulinCalculationRequest req = captor.getValue();
        assertThat(req.getClientTimeInfo()).as("without this the ISF window resolves at server 'now'").isNotNull();
        assertThat(req.getClientTimeInfo().toLocalDateTime()).isEqualTo(dinner);
        assertThat(req.getCarbs()).as("correction-only estimate carries no meal").isEqualTo(0.0);
        assertThat(req.getCurrentGlucose()).isEqualTo(11.5);
    }

    @Test
    @DisplayName("#25: COB comes from the canonical nutrition-aware entries, not a local converter")
    void buildContext_cobUsesCanonicalEntries() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 12, 0);
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId))
                .thenReturn(List.of(chartRowAt(8.0, now.minusMinutes(1))));

        // A meal the shared mapper would enrich with a GI-aware absorption curve. The local
        // converter drops those fields, so the two builders disagree for exactly this note.
        CarbsEntry enriched = CarbsEntry.builder()
                .timestamp(now.minusHours(1)).carbs(60.0).userId(userId).build();
        enriched.setAbsorptionMode("GI_GL_ENHANCED");
        GlucoseCalculationsService.ActiveCobIobInputs inputs =
                new GlucoseCalculationsService.ActiveCobIobInputs(
                        List.of(), List.of(enriched), List.of(), defaultUserSettings, defaultRapidIob);
        when(calculationsService.activeCobIobInputs(eq(userId), any())).thenReturn(inputs);
        when(carbsOnBoardService.calculateTotalCarbsOnBoard(eq(List.of(enriched)), any(), any(UserSettingsDTO.class)))
                .thenReturn(42.0);

        AnalysisContext ctx = service.buildContext(userId, 12, now);

        assertThat(ctx.getActiveCob())
                .as("activeCob must be computed from the canonical entries the dashboard uses")
                .isEqualTo(42.0);
    }

    @Test
    @DisplayName("#22: the 2h prediction comes from the canonical prediction service, not a local formula")
    void buildContext_predictionDelegatesToCalculationsService() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 12, 0);
        when(chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId))
                .thenReturn(List.of(chartRowAt(11.5, now.minusMinutes(1))));
        // The canonical path says 9.9. The old local formula would say 11.5 (latest, with zero
        // COB/IOB), so this can only pass if the value is actually read from the delegate.
        when(calculationsService.calculateGlucoseData(any()))
                .thenReturn(GlucoseCalculationsResponse.builder()
                        .twoHourPrediction(9.9)
                        .activeCarbsOnBoard(0.0)
                        .activeInsulinOnBoard(0.0)
                        .build());

        AnalysisContext ctx = service.buildContext(userId, 12, now);

        assertThat(ctx.getPredictedGlucose2h())
                .as("predictedGlucose2h must be the canonical twoHourPrediction, not a second model")
                .isEqualTo(9.9);
    }

    // C2 (9b6ccd4) required that this path never substitute the base ISF where a meal-window
    // value exists. Since #22 the dose is computed by InsulinCalculatorService, so C2 is now
    // guarded in two halves: this class pins the request to the analysis instant
    // (buildContext_doseRequestCarriesAnalysisInstant above), and the calculator resolves the
    // window ISF at that instant (InsulinCalculatorServiceIsfWindowTest). Asserting the ISF
    // arithmetic here would now only assert a mock.

    // ---- rescue-carb marker ----

    /**
     * The advisor's COB must see a rescue carb as a rescue. This aggregator hand-builds its own
     * {@code CarbsEntry} rather than routing through {@code NoteToCarbsEntryMapper}, and once did so
     * without an {@code absorptionMode} at all - so {@code RescueCarbProfile.isRescue(null)} was
     * false, and the advisor was told a 15 g rescue still had ~11 g on board 30 minutes later while
     * the dashboard, which does route through the mapper, reported it three-quarters absorbed.
     */
    // Rescue-carb marking moved to the shared NoteToCarbsEntryMapper when this service stopped
    // building its own CarbsEntry (#25). Its coverage lives in NoteToCarbsEntryMapperRescueTest,
    // which asserts the marker on hypo_treatment notes and DEFAULT_DECAY on ordinary ones.

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
