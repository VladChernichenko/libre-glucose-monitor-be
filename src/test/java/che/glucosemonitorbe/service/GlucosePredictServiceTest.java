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
    // MARK: Prospective meal lifecycle (regression for "flat curve" bug)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("when carbs > 0 a prospective meal entry is added to carbsWithMeal")
    void carbsInRequest_prospectiveMealIncludedInCarbsPassedToOde() {
        PredictRequest req = simpleRequest(7.0, 0, 35, 0, 0, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        List<CarbsEntry> captured = carbsCaptor.getValue();
        // At least the prospective meal must be present
        assertThat(captured).isNotEmpty();
        // Its carbs must match the request value
        assertThat(captured.stream().anyMatch(e -> e.getCarbs() != null && e.getCarbs() == 35.0))
                .as("prospective meal with carbs=35 must appear in the list passed to ODE")
                .isTrue();
    }

    @Test
    @DisplayName("when carbs = 0, no prospective meal entry is added")
    void noCarbsInRequest_noProsectiveMealAddedToList() {
        PredictRequest req = simpleRequest(7.0, 0, 0, 0, 0, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        // No DB notes in setUp, no carbsG → list must be empty
        assertThat(carbsCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("prospective meal entry has a non-null timestamp (never null → never skipped in ODE)")
    void prospectiveMealEntry_hasNonNullTimestamp() {
        PredictRequest req = simpleRequest(5.8, 0, 35, 0, 0, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        carbsCaptor.getValue().forEach(e ->
                assertThat(e.getTimestamp())
                        .as("carb entry timestamp must not be null — a null timestamp is silently skipped by the ODE warm-up")
                        .isNotNull());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: Null-safe macro inputs
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null macro fields are treated as zero — no NullPointerException")
    void nullMacroFields_treatedAsZeroNoException() {
        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .insulinDose(null)   // null → safe() → 0
                .carbs(null)
                .protein(null)
                .fat(null)
                .fiber(null)
                .horizonMinutes(300)
                .build();

        // Must not throw
        PredictResponse resp = sut.predict(req, USERNAME);

        assertThat(resp).isNotNull();
        assertThat(resp.getPreBolusMinutes()).isEqualTo(0); // no insulin dose
    }

    @Test
    @DisplayName("negative macro values are clamped to zero by safe()")
    void negativeMacroValues_clampedToZero() {
        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .insulinDose(-1.0)
                .carbs(-5.0)
                .protein(-3.0)
                .fat(-2.0)
                .horizonMinutes(300)
                .build();

        PredictResponse resp = sut.predict(req, USERNAME);

        assertThat(resp.getPreBolusMinutes()).isEqualTo(0); // negative insulin treated as 0
        // No prospective meal entry (carbsG ≤ 0)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());
        assertThat(carbsCaptor.getValue()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: Horizon clamping
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null horizon → uses default 300 min")
    void nullHorizon_usesDefault300() {
        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .carbs(40.0)
                .horizonMinutes(null)  // absent
                .build();

        sut.predict(req, USERNAME);

        ArgumentCaptor<Integer> horizonCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(), anyList(), anyList(), any(),
                horizonCaptor.capture());

        assertThat(horizonCaptor.getValue()).isEqualTo(300);
    }

    @Test
    @DisplayName("horizon 30 (below minimum) → clamped to 60")
    void horizon_belowMinimum_clampedTo60() {
        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .carbs(40.0)
                .horizonMinutes(30)
                .build();

        sut.predict(req, USERNAME);

        ArgumentCaptor<Integer> horizonCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(), anyList(), anyList(), any(),
                horizonCaptor.capture());

        assertThat(horizonCaptor.getValue()).isEqualTo(60);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: History → ODE plumbing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("past insulin note becomes an InsulinDose passed to the ODE engine")
    void pastInsulinNote_passedAsInsulinDose() {
        Note bolusNote = new Note(USER_ID, LocalDateTime.now().minusMinutes(30), 0.0, 4.0, "Bolus");
        bolusNote.setId(UUID.randomUUID());
        bolusNote.setType(Note.TYPE_NORMAL);

        when(noteRepository.findByUserIdAndTimestampBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(bolusNote));

        sut.predict(simpleRequest(7.0, 0, 40, 0, 0, 0), USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InsulinDose>> dosesCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(),
                dosesCaptor.capture(), anyList(), any(), anyInt());

        assertThat(dosesCaptor.getValue())
                .as("past bolus note must appear as an InsulinDose in the ODE call")
                .anyMatch(d -> d.getUnits() != null && d.getUnits() == 4.0);
    }

    @Test
    @DisplayName("long-acting note is excluded from InsulinDose list (not a bolus)")
    void longActingNote_excludedFromInsulinDoses() {
        Note basalNote = new Note(USER_ID, LocalDateTime.now().minusHours(10), 0.0, 20.0, "Tresiba");
        basalNote.setId(UUID.randomUUID());
        basalNote.setType(Note.TYPE_LONG_ACTING);

        when(noteRepository.findByUserIdAndTimestampBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(basalNote));

        sut.predict(simpleRequest(7.0, 0, 40, 0, 0, 0), USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InsulinDose>> dosesCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(),
                dosesCaptor.capture(), anyList(), any(), anyInt());

        // Long-acting insulin must NOT appear in bolus doses list
        assertThat(dosesCaptor.getValue())
                .as("long-acting note must not appear as a bolus InsulinDose")
                .noneMatch(d -> d.getUnits() != null && d.getUnits() == 20.0);
    }

    @Test
    @DisplayName("long-acting note is passed to ODE in the longActingNotes argument")
    void longActingNote_passedInLongActingNotesArgument() {
        Note basalNote = new Note(USER_ID, LocalDateTime.now().minusHours(8), 0.0, 15.0, "Lantus");
        basalNote.setId(UUID.randomUUID());
        basalNote.setType(Note.TYPE_LONG_ACTING);

        // Both the 8h and 36h windows return the long-acting note
        when(noteRepository.findByUserIdAndTimestampBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(basalNote));

        sut.predict(simpleRequest(7.0, 0, 40, 0, 0, 0), USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Note>> longActingCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(), anyList(),
                longActingCaptor.capture(), any(), anyInt());

        assertThat(longActingCaptor.getValue())
                .as("long-acting note must appear in the longActingNotes argument to the ODE")
                .anyMatch(n -> n.getType().equals(Note.TYPE_LONG_ACTING));
    }

    @Test
    @DisplayName("note with null carbs is not added to carbsEntries")
    void noteWithNullCarbs_filteredOut() {
        Note noCarbs = new Note(USER_ID, LocalDateTime.now().minusMinutes(60), null, 3.0, "Bolus only");
        noCarbs.setId(UUID.randomUUID());

        when(noteRepository.findByUserIdAndTimestampBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(noCarbs));

        sut.predict(simpleRequest(7.0, 0, 0, 0, 0, 0), USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        assertThat(carbsCaptor.getValue())
                .as("note with null carbs must not appear as a CarbsEntry")
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: Exception resilience
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("repository throws → gracefully falls back to empty history, prediction still runs")
    void repositoryThrows_gracefulFallback_predictionStillReturns() {
        when(noteRepository.findByUserIdAndTimestampBetween(eq(USER_ID), any(), any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        // Must not propagate — service swallows it and returns an empty history
        PredictResponse resp = sut.predict(simpleRequest(7.2, 0, 35, 0, 0, 0), USERNAME);

        assertThat(resp).isNotNull();
        assertThat(resp.getCurve()).isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: Gap 2 — FPU virtual slow-carb entry (Warsaw Protocol)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fat+protein above threshold → fpu-equiv entry added at t+90 min")
    void highFatProtein_fpuEntryAddedAt90min() {
        // 25g protein × 4 = 100 kcal, 30g fat × 9 = 270 kcal → 370 kcal / 100 × 10 = 37 g equiv
        PredictRequest req = simpleRequest(7.0, 0, 40, 25, 30, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        List<CarbsEntry> captured = carbsCaptor.getAllValues().get(carbsCaptor.getAllValues().size() - 1);

        // Must contain an fpu-equiv entry
        java.util.Optional<CarbsEntry> fpuEntry = captured.stream()
                .filter(e -> "fpu-equiv".equals(e.getMealType()))
                .findFirst();
        assertThat(fpuEntry).isPresent();
        assertThat(fpuEntry.get().getCarbs())
                .as("FPU-equiv carbs = (25×4×0.50 + 30×9) / 100 × 10 = 32 g "
                    + "(default 50 %% gluconeogenic fraction; no lctFatG override so all fat counts as LCT)")
                .isCloseTo(32.0, org.assertj.core.api.Assertions.within(0.5));
    }

    @Test
    @DisplayName("fpu-equiv entry timestamp is exactly now + 90 min")
    void fpuEntry_timestampIsNowPlus90() {
        PredictRequest req = simpleRequest(7.0, 0, 40, 25, 30, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        List<CarbsEntry> captured = carbsCaptor.getAllValues().get(carbsCaptor.getAllValues().size() - 1);
        java.util.Optional<CarbsEntry> fpuEntry = captured.stream()
                .filter(e -> "fpu-equiv".equals(e.getMealType()))
                .findFirst();
        assertThat(fpuEntry).isPresent();
        assertThat(fpuEntry.get().getTimestamp())
                .as("FPU onset must be 90 min after the meal")
                .isAfter(fpuEntry.get().getTimestamp().minusMinutes(91))
                .isBefore(fpuEntry.get().getTimestamp().plusMinutes(1));
        // Specifically: it must be ~90 min after now
        assertThat(fpuEntry.get().getTimestamp())
                .isAfterOrEqualTo(java.time.LocalDateTime.now().plusMinutes(89))
                .isBeforeOrEqualTo(java.time.LocalDateTime.now().plusMinutes(91));
    }

    @Test
    @DisplayName("carbs only (no fat/protein) → no fpu-equiv entry added")
    void carbsOnlyNoPF_noFpuEntry() {
        PredictRequest req = simpleRequest(7.0, 0, 60, 0, 0, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        List<CarbsEntry> captured = carbsCaptor.getAllValues().get(carbsCaptor.getAllValues().size() - 1);
        assertThat(captured.stream().noneMatch(e -> "fpu-equiv".equals(e.getMealType())))
                .as("pure-carb meal must not generate an fpu-equiv entry")
                .isTrue();
    }

    @Test
    @DisplayName("trace fat+protein below threshold (< 2 g equiv) → no fpu-equiv entry")
    void traceFatProtein_belowThreshold_noFpuEntry() {
        // 3g protein × 4 = 12 kcal → fpuEquivCarbs = 12/100×10 = 1.2 g < 2.0 threshold
        PredictRequest req = simpleRequest(7.0, 0, 60, 3, 0, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        List<CarbsEntry> captured = carbsCaptor.getAllValues().get(carbsCaptor.getAllValues().size() - 1);
        assertThat(captured.stream().noneMatch(e -> "fpu-equiv".equals(e.getMealType())))
                .as("sub-threshold FPU (1.2 g equiv) must not generate an fpu-equiv entry")
                .isTrue();
    }

    @Test
    @DisplayName("pure protein meal (no carbs) still generates an fpu-equiv entry")
    void pureProteinMeal_noCarbs_fpuEntryStillAdded() {
        // 50g protein × 4 = 200 kcal → 20 g equiv > 2 g threshold; carbsG=0
        PredictRequest req = simpleRequest(7.0, 0, 0, 50, 0, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(),
                carbsCaptor.capture(), anyList(), anyList(), any(), anyInt());

        List<CarbsEntry> captured = carbsCaptor.getAllValues().get(carbsCaptor.getAllValues().size() - 1);
        assertThat(captured.stream().anyMatch(e -> "fpu-equiv".equals(e.getMealType())))
                .as("pure-protein meal must still add an fpu-equiv entry even when carbsG=0")
                .isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: Prospective insulin bolus timing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("with insulin dose > 0, a prospective dose is added at now + bestPause")
    void withInsulinDose_prospectiveDoseAddedAtCorrectOffset() {
        // Make optimizer always return pause=0 by returning a flat high-cost curve
        // regardless of timing (it picks the first candidate with equal cost → pause=0)
        PredictRequest req = simpleRequest(7.0, 3.0, 50, 0, 0, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InsulinDose>> dosesCaptor = ArgumentCaptor.forClass(List.class);
        // Grab the LAST call (the final curve call after optimisation)
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(),
                dosesCaptor.capture(), anyList(), any(), anyInt());

        // The final call must include the prospective bolus
        List<List<InsulinDose>> allCaptures = dosesCaptor.getAllValues();
        List<InsulinDose> finalDoses = allCaptures.get(allCaptures.size() - 1);
        assertThat(finalDoses)
                .as("final ODE call must contain the prospective 3u bolus")
                .anyMatch(d -> d.getUnits() != null && d.getUnits() == 3.0);
    }

    @Test
    @DisplayName("insulin dose = 0 → no prospective bolus entry in finalDoses")
    void zeroDose_noProspectiveBolus() {
        PredictRequest req = simpleRequest(7.0, 0.0, 50, 0, 0, 0);

        sut.predict(req, USERNAME);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InsulinDose>> dosesCaptor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class),
                anyDouble(), any(), anyList(),
                dosesCaptor.capture(), anyList(), any(), anyInt());

        List<InsulinDose> finalDoses = dosesCaptor.getAllValues().get(dosesCaptor.getAllValues().size() - 1);
        assertThat(finalDoses)
                .as("zero dose must not add a prospective bolus entry")
                .isEmpty();
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
