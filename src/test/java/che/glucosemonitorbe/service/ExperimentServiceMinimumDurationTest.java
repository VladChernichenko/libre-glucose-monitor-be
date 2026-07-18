package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.ExperimentResultDTO;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.entity.Experiment;
import che.glucosemonitorbe.entity.Experiment.Status;
import che.glucosemonitorbe.entity.Experiment.Type;
import che.glucosemonitorbe.entity.ExperimentReading;
import che.glucosemonitorbe.repository.ExperimentRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pins the minimum-elapsed-time contract on {@code ExperimentService.completeExperiment}.
 *
 * <p>Before this contract, an experiment could be completed as soon as 2 readings were
 * captured - for a Basal Rate Check that meant a "result" after ~62 minutes (baseline +
 * first hourly checkpoint), even though the documented protocol requires 4-6 hours. The
 * computed max-min delta is mathematically valid but clinically meaningless over 1 h.</p>
 *
 * <p>Per-type minima (matches {@code ExperimentService.MIN_ELAPSED_MINUTES}):</p>
 * <ul>
 *   <li>BASAL_CHECK - 180 min (3 h floor; protocol target 4-6 h)</li>
 *   <li>CARB_FACTOR - 60 min</li>
 *   <li>ISF_ONE_UNIT - 180 min (3 h floor; protocol target 4-5 h)</li>
 * </ul>
 *
 * <p><b>RED before fix, GREEN after.</b> The "premature" cases below will start throwing
 * 400 once the minimum-elapsed gate lands; they currently pass through to the result
 * computation and yield a useless answer.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExperimentServiceMinimumDurationTest {

    @Mock private ExperimentRepository experimentRepository;
    @Mock private NoteRepository noteRepository;
    @Mock private CarbsOnBoardService cobService;
    @Mock private InsulinCalculatorService insulinCalculatorService;
    @Mock private UserSettingsService userSettingsService;
    @Mock private GlucoseCalculationsService calculationsService;

    private ExperimentService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ExperimentService(
                experimentRepository, noteRepository,
                cobService, insulinCalculatorService,
                userSettingsService, calculationsService);
        when(experimentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Default: flat 4h forecast so basal look-ahead does not overturn observed flatness.
        when(calculationsService.calculateGlucoseData(any())).thenReturn(flatForecast(7.0));
    }

    private static GlucoseCalculationsResponse flatForecast(double g) {
        return GlucoseCalculationsResponse.builder()
                .currentGlucose(g)
                .fourHourPrediction(g)
                .predictionTrend("stable")
                .predictionPath(List.of(
                        PredictionPointDTO.builder().predictedGlucose(g).build(),
                        PredictionPointDTO.builder().predictedGlucose(g).build()))
                .build();
    }

    private static GlucoseCalculationsResponse risingForecast(double from, double to) {
        return GlucoseCalculationsResponse.builder()
                .currentGlucose(from)
                .fourHourPrediction(to)
                .predictionTrend("rising")
                .predictionPath(List.of(
                        PredictionPointDTO.builder().predictedGlucose(from).build(),
                        PredictionPointDTO.builder().predictedGlucose(to).build()))
                .build();
    }

    private static GlucoseCalculationsResponse fallingForecast(double from, double to) {
        return GlucoseCalculationsResponse.builder()
                .currentGlucose(from)
                .fourHourPrediction(to)
                .predictionTrend("falling")
                .predictionPath(List.of(
                        PredictionPointDTO.builder().predictedGlucose(from).build(),
                        PredictionPointDTO.builder().predictedGlucose(to).build()))
                .build();
    }

    // -- BASAL_CHECK ----------------------------------------------------------

    @Test
    @DisplayName("BASAL_CHECK with 62 min elapsed -> 400 (below 180 min minimum)")
    void basalCheck_completedTooEarly_isRejected() {
        Experiment exp = inProgress(Type.BASAL_CHECK, 62, /* readings */ 7.0, 7.5);
        stubFetch(exp);

        assertThatThrownBy(() -> service.completeExperiment(exp.getId(), userId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("minimum")
                .hasMessageContaining("BASAL_CHECK");
    }

    @Test
    @DisplayName("BASAL_CHECK with 180 min elapsed -> completes (at the floor)")
    void basalCheck_atMinimum_completes() {
        Experiment exp = inProgress(Type.BASAL_CHECK, 180, 7.0, 7.5);
        stubFetch(exp);

        ExperimentResultDTO result = service.completeExperiment(exp.getId(), userId, null);

        assertThat(result).isNotNull();
        assertThat(result.getIsStable()).isTrue();
    }

    @Test
    @DisplayName("BASAL_CHECK with 4h elapsed -> completes (well past the floor)")
    void basalCheck_atProtocolTarget_completes() {
        Experiment exp = inProgress(Type.BASAL_CHECK, 240, 6.5, 7.8);
        stubFetch(exp);

        ExperimentResultDTO result = service.completeExperiment(exp.getId(), userId, null);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("BASAL_CHECK flat observed + flat forecast -> stable, no dose change advice")
    void basalCheck_flat_isStable_noAdjustmentAdvice() {
        Experiment exp = inProgress(Type.BASAL_CHECK, 240, 7.0, 7.2, 7.1);
        stubFetch(exp);
        when(calculationsService.calculateGlucoseData(any())).thenReturn(flatForecast(7.1));

        ExperimentResultDTO result = service.completeExperiment(exp.getId(), userId, null);

        assertThat(result.getIsStable()).isTrue();
        assertThat(result.getExplanation())
                .containsIgnoringCase("stable")
                .doesNotContain("increasing")
                .doesNotContain("decreasing")
                .doesNotContain("Recheck tomorrow");
        assertThat(exp.getResultNotes()).contains("advice=review").contains("stable=true");
    }

    @Test
    @DisplayName("BASAL_CHECK rising 4h forecast -> unstable, advise +2 U and recheck tomorrow")
    void basalCheck_risingForecast_advisesIncrease() {
        // Observed readings stay within 1.7; rising look-ahead fails the check.
        Experiment exp = inProgress(Type.BASAL_CHECK, 240, 7.0, 7.2, 7.1);
        stubFetch(exp);
        when(calculationsService.calculateGlucoseData(any())).thenReturn(risingForecast(7.1, 9.5));

        ExperimentResultDTO result = service.completeExperiment(exp.getId(), userId, null);

        assertThat(result.getIsStable()).isFalse();
        assertThat(result.getExplanation())
                .contains("not flat enough")
                .contains("increasing your long-acting basal dose by 2 units")
                .contains("Recheck tomorrow");
        assertThat(exp.getResultNotes()).contains("advice=increase").contains("stable=false");
    }

    @Test
    @DisplayName("BASAL_CHECK falling 4h forecast -> unstable, advise -2 U and recheck tomorrow")
    void basalCheck_fallingForecast_advisesDecrease() {
        Experiment exp = inProgress(Type.BASAL_CHECK, 240, 8.0, 7.9, 7.8);
        stubFetch(exp);
        when(calculationsService.calculateGlucoseData(any())).thenReturn(fallingForecast(7.8, 5.5));

        ExperimentResultDTO result = service.completeExperiment(exp.getId(), userId, null);

        assertThat(result.getIsStable()).isFalse();
        assertThat(result.getExplanation())
                .contains("not flat enough")
                .contains("decreasing your long-acting basal dose by 2 units")
                .contains("Recheck tomorrow");
        assertThat(exp.getResultNotes()).contains("advice=decrease").contains("stable=false");
    }

    @Test
    @DisplayName("BASAL_CHECK rising observed drift (flat forecast) -> unstable, advise +2 U")
    void basalCheck_risingObserved_advisesIncrease() {
        // Delta 2.5 > 1.7 even with a flat forecast.
        Experiment exp = inProgress(Type.BASAL_CHECK, 240, 7.0, 8.5, 9.5);
        stubFetch(exp);
        when(calculationsService.calculateGlucoseData(any())).thenReturn(flatForecast(9.5));

        ExperimentResultDTO result = service.completeExperiment(exp.getId(), userId, null);

        assertThat(result.getIsStable()).isFalse();
        assertThat(result.getExplanation())
                .contains("increasing your long-acting basal dose by 2 units")
                .contains("Recheck tomorrow");
        assertThat(exp.getResultNotes()).contains("advice=increase");
    }

    @Test
    @DisplayName("BASAL_CHECK falling observed drift (flat forecast) -> unstable, advise -2 U")
    void basalCheck_fallingObserved_advisesDecrease() {
        Experiment exp = inProgress(Type.BASAL_CHECK, 240, 9.5, 8.0, 7.0);
        stubFetch(exp);
        when(calculationsService.calculateGlucoseData(any())).thenReturn(flatForecast(7.0));

        ExperimentResultDTO result = service.completeExperiment(exp.getId(), userId, null);

        assertThat(result.getIsStable()).isFalse();
        assertThat(result.getExplanation())
                .contains("decreasing your long-acting basal dose by 2 units")
                .contains("Recheck tomorrow");
        assertThat(exp.getResultNotes()).contains("advice=decrease");
    }

    // -- CARB_FACTOR ----------------------------------------------------------

    @Test
    @DisplayName("CARB_FACTOR with 30 min elapsed -> 400 (below 60 min minimum)")
    void carbFactor_completedTooEarly_isRejected() {
        Experiment exp = inProgress(Type.CARB_FACTOR, 30, 6.0, 8.5);
        exp.setGramsConsumed(15.0);
        stubFetch(exp);

        assertThatThrownBy(() -> service.completeExperiment(exp.getId(), userId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("minimum")
                .hasMessageContaining("CARB_FACTOR");
    }

    @Test
    @DisplayName("CARB_FACTOR with 60 min elapsed -> completes")
    void carbFactor_atMinimum_completes() {
        Experiment exp = inProgress(Type.CARB_FACTOR, 60, 6.0, 9.2);
        exp.setGramsConsumed(15.0);
        stubFetch(exp);

        ExperimentResultDTO result = service.completeExperiment(exp.getId(), userId, null);

        assertThat(result.getComputedCarbRatio()).isGreaterThan(0);
    }

    // -- ISF_ONE_UNIT ---------------------------------------------------------

    @Test
    @DisplayName("ISF_ONE_UNIT with 90 min elapsed -> 400 (below 180 min minimum)")
    void isf_completedTooEarly_isRejected() {
        Experiment exp = inProgress(Type.ISF_ONE_UNIT, 90, 12.0, 9.0);
        exp.setUnitsInjected(1.0);
        stubFetch(exp);

        assertThatThrownBy(() -> service.completeExperiment(exp.getId(), userId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("minimum")
                .hasMessageContaining("ISF_ONE_UNIT");
    }

    @Test
    @DisplayName("ISF_ONE_UNIT with 180 min elapsed -> completes")
    void isf_atMinimum_completes() {
        Experiment exp = inProgress(Type.ISF_ONE_UNIT, 180, 12.0, 8.0);
        exp.setUnitsInjected(1.0);
        stubFetch(exp);

        ExperimentResultDTO result = service.completeExperiment(exp.getId(), userId, null);

        assertThat(result.getComputedIsf()).isGreaterThan(0);
    }

    // -- Existing contracts still enforced ------------------------------------

    @Test
    @DisplayName("Existing contract preserved: < 2 readings -> 400 (readings check still fires)")
    void belowMinReadings_stillRejected() {
        Experiment exp = inProgress(Type.BASAL_CHECK, 300 /* well past floor */, 7.0);
        stubFetch(exp);

        assertThatThrownBy(() -> service.completeExperiment(exp.getId(), userId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least 2 readings");
    }

    @Test
    @DisplayName("Existing contract preserved: completing non-IN_PROGRESS -> 400")
    void notInProgress_stillRejected() {
        Experiment exp = inProgress(Type.BASAL_CHECK, 300, 7.0, 7.5);
        exp.setStatus(Status.COMPLETED);
        stubFetch(exp);

        assertThatThrownBy(() -> service.completeExperiment(exp.getId(), userId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not IN_PROGRESS");
    }

    // ---
    // Helpers
    // ---

    private Experiment inProgress(Type type, int elapsedMinutes, double... glucoseValues) {
        UUID id = UUID.randomUUID();
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(elapsedMinutes);
        Experiment exp = Experiment.builder()
                .id(id)
                .userId(userId)
                .type(type)
                .status(Status.IN_PROGRESS)
                .startedAt(startedAt)
                .createdAt(startedAt)
                .updatedAt(LocalDateTime.now())
                .build();

        List<ExperimentReading> readings = new ArrayList<>();
        for (int i = 0; i < glucoseValues.length; i++) {
            readings.add(ExperimentReading.builder()
                    .id(UUID.randomUUID())
                    .experiment(exp)
                    .recordedAt(startedAt.plusMinutes((long) i * 60))
                    .glucoseMmol(glucoseValues[i])
                    .minutesElapsed(i * 60)
                    .label(i == 0 ? "Baseline" : "T+" + (i * 60))
                    .build());
        }
        exp.setReadings(readings);
        return exp;
    }

    private void stubFetch(Experiment exp) {
        when(experimentRepository.findByIdAndUserId(exp.getId(), userId)).thenReturn(Optional.of(exp));
    }
}
