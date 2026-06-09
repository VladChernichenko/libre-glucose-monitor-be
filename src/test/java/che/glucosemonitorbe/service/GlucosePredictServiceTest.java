package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictRequest;
import che.glucosemonitorbe.dto.PredictResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.MacroNutrientGastricModel;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * London-School unit tests for {@link GlucosePredictService}.
 *
 * <p>All collaborators are mocked — the ODE engine, parameter service, user
 * service, and note repository. We verify:
 * <ul>
 *   <li>Macro modulation: when macros are supplied, tMaxG in the
 *       {@link HovorkaParameters} passed to the ODE engine differs from the base
 *       value (i.e. {@link MacroNutrientGastricModel} is applied).</li>
 *   <li>No macros: base tMaxG is passed through unchanged.</li>
 *   <li>No insulin dose: pre-bolus pause = 0, optimizer is never called.</li>
 *   <li>Insulin dose present: optimizer calls are made and preBolusMinutes is
 *       one of the candidate values [0, 5, …, 30].</li>
 *   <li>Bolus strategy: SQUARE_WAVE for high-fat/high-protein meals, NORMAL otherwise.</li>
 *   <li>Response structure: curve, preBolusMinutes, bolusStrategy, tMaxGUsed, betaWeighted
 *       are all populated.</li>
 * </ul>
 */
class GlucosePredictServiceTest {

    private static final UUID   USER_ID  = UUID.randomUUID();
    private static final String USERNAME = "test-user";

    // ── Mocks ────────────────────────────────────────────────────────────────
    private HovorkaGlucosePredictionService hovorkaService;
    private HovorkaParameterService         paramService;
    private UserService                     userService;
    private NoteRepository                  noteRepository;

    private GlucosePredictService sut;

    // ── Base params (simulating user's calibrated settings) ─────────────────
    private static final HovorkaParameters BASE_PARAMS = new HovorkaParameters(
            0.16 * 70,   // vG
            0.0097 * 70, // f01
            0.0097 * 70, // egpNet
            0.066, 0.066,
            45.0 / 1.68, // tMaxG ≈ 26.8 min (from 45-min carb half-life)
            0.80,
            2.2,
            70.0
    );

    @BeforeEach
    void setUp() {
        hovorkaService = mock(HovorkaGlucosePredictionService.class);
        paramService   = mock(HovorkaParameterService.class);
        userService    = mock(UserService.class);
        noteRepository = mock(NoteRepository.class);

        sut = new GlucosePredictService(hovorkaService, paramService, userService, noteRepository);

        // Default stubs
        UserDto userDto = new UserDto();
        userDto.setId(USER_ID);
        when(userService.getUserByUsername(USERNAME)).thenReturn(userDto);

        when(paramService.buildForUser(USER_ID)).thenReturn(BASE_PARAMS);

        when(noteRepository.findByUserIdAndTimestampBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of());

        // ODE engine stub: always returns two fixed points
        List<PredictionPointDTO> fixedCurve = List.of(
                PredictionPointDTO.builder()
                        .timestamp(LocalDateTime.now().plusMinutes(5))
                        .predictedGlucose(7.0)
                        .absorptionMode("HOVORKA_2COMP")
                        .build(),
                PredictionPointDTO.builder()
                        .timestamp(LocalDateTime.now().plusMinutes(10))
                        .predictedGlucose(6.8)
                        .absorptionMode("HOVORKA_2COMP")
                        .build()
        );
        when(hovorkaService.buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(LocalDateTime.class),
                anyList(), anyList(), anyList(),
                eq(USER_ID), anyInt()))
                .thenReturn(fixedCurve);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: Response structure
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("response always contains curve, preBolusMinutes, bolusStrategy, tMaxGUsed, betaWeighted")
    void response_containsAllFields() {
        PredictResponse resp = sut.predict(simpleRequest(7.2, 0, 40, 0, 0, 0), USERNAME);

        assertThat(resp.getCurve()).isNotEmpty();
        assertThat(resp.getPreBolusMinutes()).isNotNull();
        assertThat(resp.getBolusStrategy()).isNotNull();
        assertThat(resp.getTMaxGUsed()).isNotNull();
        assertThat(resp.getBetaWeighted()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: tMaxG modulation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("when macros provided, ODE engine receives a modulated tMaxG (> base)")
    void macrosProvided_odeReceivesModulatedTMaxG() {
        // Buffalo-wings-style meal: high fat + high protein → longer gastric emptying
        PredictRequest req = simpleRequest(7.0, 0, 40, 30, 25, 5); // carbs/protein/fat/fiber

        sut.predict(req, USERNAME);

        ArgumentCaptor<HovorkaParameters> paramsCaptor = ArgumentCaptor.forClass(HovorkaParameters.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                paramsCaptor.capture(),
                anyDouble(), any(), anyList(), anyList(), anyList(), any(), anyInt());

        double capturedTMaxG = paramsCaptor.getValue().tMaxG();
        assertThat(capturedTMaxG)
                .as("HFHP meal must elongate tMaxG beyond the pure-carb base")
                .isGreaterThan(BASE_PARAMS.tMaxG());
    }

    @Test
    @DisplayName("when no macros provided, ODE engine receives the user's base tMaxG")
    void noMacros_odeReceivesBaseTMaxG() {
        PredictRequest req = simpleRequest(7.0, 0, 0, 0, 0, 0); // zero macros

        sut.predict(req, USERNAME);

        ArgumentCaptor<HovorkaParameters> paramsCaptor = ArgumentCaptor.forClass(HovorkaParameters.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                paramsCaptor.capture(),
                anyDouble(), any(), anyList(), anyList(), anyList(), any(), anyInt());

        double capturedTMaxG = paramsCaptor.getValue().tMaxG();
        assertThat(capturedTMaxG)
                .as("No macros → tMaxG must equal the user's base value")
                .isEqualTo(BASE_PARAMS.tMaxG());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: Pre-bolus optimisation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no insulin dose → preBolusMinutes=0, ODE engine called exactly once (final curve only)")
    void noInsulinDose_noPrebolusCalls() {
        PredictRequest req = simpleRequest(6.0, 0, 50, 0, 0, 0); // insulinDose=0

        PredictResponse resp = sut.predict(req, USERNAME);

        assertThat(resp.getPreBolusMinutes()).isEqualTo(0);
        // Only one call: the final curve (no optimization rounds)
        verify(hovorkaService, times(1)).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(), anyList(), anyList(), any(), anyInt());
    }

    @Test
    @DisplayName("with insulin dose → optimizer runs 7 candidates + 1 final = 8 ODE calls")
    void withInsulinDose_optimizerRunsSevenPlusFinalCall() {
        PredictRequest req = simpleRequest(8.5, 4.0, 60, 0, 0, 0); // insulinDose=4u

        sut.predict(req, USERNAME);

        // 7 candidates [0,5,10,15,20,25,30] + 1 final = 8 total calls
        verify(hovorkaService, times(8)).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(), anyList(), anyList(), any(), anyInt());
    }

    @Test
    @DisplayName("preBolusMinutes is one of the candidate values [0,5,10,15,20,25,30]")
    void preBolusMinutes_isOneOfCandidates() {
        PredictRequest req = simpleRequest(7.5, 3.0, 50, 0, 0, 0);

        PredictResponse resp = sut.predict(req, USERNAME);

        assertThat(resp.getPreBolusMinutes())
                .as("preBolusMinutes must be one of the candidate pauses")
                .isIn(0, 5, 10, 15, 20, 25, 30);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: Bolus strategy
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("high-fat + high-protein meal → SQUARE_WAVE strategy")
    void highFatHighProtein_squareWaveStrategy() {
        PredictRequest req = simpleRequest(7.0, 4.0, 30, 25, 30, 3); // fat=30g, protein=25g

        PredictResponse resp = sut.predict(req, USERNAME);

        assertThat(resp.getBolusStrategy()).isEqualTo("SQUARE_WAVE");
    }

    @Test
    @DisplayName("simple carb meal → NORMAL strategy")
    void simpleCarbMeal_normalStrategy() {
        PredictRequest req = simpleRequest(7.0, 4.0, 60, 5, 3, 2); // carb-heavy, low fat/protein

        PredictResponse resp = sut.predict(req, USERNAME);

        assertThat(resp.getBolusStrategy()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("very high protein alone (>40g) → SQUARE_WAVE strategy")
    void veryHighProteinAlone_squareWaveStrategy() {
        PredictRequest req = simpleRequest(7.0, 4.0, 10, 45, 5, 1); // protein=45g, low fat

        PredictResponse resp = sut.predict(req, USERNAME);

        assertThat(resp.getBolusStrategy()).isEqualTo("SQUARE_WAVE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: betaWeighted
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pure carb meal → betaWeighted ≈ 1.05 (Elashoff carb constant)")
    void pureCarbMeal_betaWeightedIsCarb() {
        PredictRequest req = simpleRequest(7.0, 0, 60, 0, 0, 0);

        PredictResponse resp = sut.predict(req, USERNAME);

        assertThat(resp.getBetaWeighted())
                .isCloseTo(MacroNutrientGastricModel.BETA_CARBS, org.assertj.core.api.Assertions.within(0.02));
    }

    @Test
    @DisplayName("pure fat meal → betaWeighted ≈ 2.20 (Elashoff fat constant)")
    void pureFatMeal_betaWeightedIsFat() {
        PredictRequest req = simpleRequest(7.0, 0, 0, 0, 60, 0);

        PredictResponse resp = sut.predict(req, USERNAME);

        assertThat(resp.getBetaWeighted())
                .isCloseTo(MacroNutrientGastricModel.BETA_FAT, org.assertj.core.api.Assertions.within(0.02));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: History integration
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("recent carb notes are loaded and passed to the ODE engine")
    void recentCarbNotes_passedToOdeEngine() {
        Note pastNote = new Note(USER_ID, LocalDateTime.now().minusMinutes(45), 30.0, 3.0, "Lunch");
        pastNote.setId(UUID.randomUUID());

        when(noteRepository.findByUserIdAndTimestampBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(pastNote));

        sut.predict(simpleRequest(7.0, 0, 40, 0, 0, 0), USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        // Must contain both the past note and the prospective meal
        assertThat(carbsCaptor.getValue())
                .as("ODE must receive the past note carbs plus the prospective meal")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("horizon clamped to [60, 480] — value 999 becomes 480")
    void horizon_clampedToMaximum() {
        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .carbs(50.0)
                .horizonMinutes(999)
                .build();

        sut.predict(req, USERNAME);

        ArgumentCaptor<Integer> horizonCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(), anyList(), anyList(), any(),
                horizonCaptor.capture());

        assertThat(horizonCaptor.getValue()).isEqualTo(480);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static PredictRequest simpleRequest(
            double glucose, double insulin,
            double carbs, double protein, double fat, double fiber) {
        return PredictRequest.builder()
                .currentGlucose(glucose)
                .insulinDose(insulin)
                .carbs(carbs)
                .protein(protein)
                .fat(fat)
                .fiber(fiber)
                .build();
    }
}
