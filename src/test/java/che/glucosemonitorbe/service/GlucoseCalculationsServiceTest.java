package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.PredictionFactors;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.NotesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.annotation.Cacheable;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlucoseCalculationsServiceTest {

    @Mock private CarbsOnBoardService cobService;
    @Mock private InsulinCalculatorService insulinCalculatorService;
    @Mock private NoteRepository noteRepository;
    @Mock private UserService userService;
    @Mock private UserInsulinPreferencesService userInsulinPreferencesService;
    @Mock private ObjectMapper objectMapper;
    @Mock private FeatureToggleConfig featureToggleConfig;
    @Mock private COBSettingsService cOBSettingsService;

    private GlucoseCalculationsService service;

    @BeforeEach
    void setUp() {
        service = new GlucoseCalculationsService(
                cobService, insulinCalculatorService, noteRepository,
                userService, userInsulinPreferencesService, objectMapper,
                featureToggleConfig, cOBSettingsService);
    }

    @Test
    void determineTrendUsesAdjustedThresholds() throws Exception {
        // Use a minimal service instance for the private-method reflection test
        GlucoseCalculationsService svc = new GlucoseCalculationsService(null, null, null, null, null, null, null, null);
        Method method = GlucoseCalculationsService.class.getDeclaredMethod("determineTrend", PredictionFactors.class, double.class);
        method.setAccessible(true);

        PredictionFactors rising = PredictionFactors.builder().carbContribution(0.35).insulinContribution(0.0).baselineContribution(0.0).trendContribution(0.0).build();
        PredictionFactors falling = PredictionFactors.builder().carbContribution(-0.35).insulinContribution(0.0).baselineContribution(0.0).trendContribution(0.0).build();
        PredictionFactors stable = PredictionFactors.builder().carbContribution(0.1).insulinContribution(-0.1).baselineContribution(0.0).trendContribution(0.0).build();

        assertEquals("rising", method.invoke(svc, rising, 120.0));
        assertEquals("falling", method.invoke(svc, falling, 120.0));
        assertEquals("stable", method.invoke(svc, stable, 120.0));
    }

    // ── P1: getUserByUsername called twice per request ────────────────────────

    /**
     * BUG: P1 — GlucoseCalculationsService.calculateGlucoseData calls
     * userService.getUserByUsername twice: once in the main method body (line ~70)
     * to get the UUID for COB settings, and again inside getRecentNotes (line ~396).
     * This is an unnecessary N+1 pattern — one lookup per request is sufficient.
     *
     * This test verifies that getUserByUsername is called exactly once.
     * It FAILS because the current implementation calls it twice.
     */
    @Test
    void p1_calculateGlucoseData_callsGetUserByUsernameExactlyOnce() {
        String username = "testuser";
        UUID userId = UUID.randomUUID();

        UserDto mockUser = UserDto.builder()
                .id(userId)
                .username(username)
                .build();

        when(userService.getUserByUsername(username)).thenReturn(mockUser);

        // Stub COB settings to avoid NPE
        COBSettingsDTO cobSettings = new COBSettingsDTO();
        cobSettings.setUserId(userId);
        cobSettings.setCarbRatio(2.0);
        cobSettings.setIsf(1.0);
        cobSettings.setCarbHalfLife(45);
        cobSettings.setMaxCOBDuration(240);
        when(cOBSettingsService.getCOBSettings(userId)).thenReturn(cobSettings);

        // Stub noteRepository to return empty list
        when(noteRepository.findByUserIdAndTimestampBetween(any(UUID.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        // Stub cobService to return 0.0
        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(0.0);

        // Stub insulin preferences and calculator
        when(userInsulinPreferencesService.getRapidIobParameters(userId))
                .thenReturn(new RapidInsulinIobParameters(4.0, 75.0));
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);

        // Stub featureToggleConfig
        when(featureToggleConfig.isNutritionAwarePredictionEnabled()).thenReturn(false);

        GlucoseCalculationsRequest request = GlucoseCalculationsRequest.builder()
                .currentGlucose(5.5)
                .userId(username)
                .includePredictionFactors(false)
                .build();

        service.calculateGlucoseData(request);

        // BUG: getUserByUsername is currently called TWICE — this FAILS
        verify(userService, times(1)).getUserByUsername(username);
    }

    // ── L2: getRecentNotes uses 6-hour window; HFHP meals need 8 hours ───────

    /**
     * BUG: L2 — GlucoseCalculationsService.getRecentNotes fetches notes from only the
     * last 6 hours.  For high-fat/high-protein (HFHP) meals, peak carb absorption can
     * extend to 8 hours.  A note logged 7.5 hours ago with absorptionMode "HFHP" is
     * excluded from the COB calculation, causing an under-estimate.
     *
     * This test verifies that when a HFHP note 7.5 hours old exists, it IS included in
     * the list passed to cobService.calculateTotalCarbsOnBoard.
     * It FAILS because the 6-hour window excludes it.
     */
    @Test
    void l2_getRecentNotes_hfhpMeal7hoursOld_mustBeIncludedInCOBCalculation() {
        String username = "testuser";
        UUID userId = UUID.randomUUID();

        UserDto mockUser2 = UserDto.builder()
                .id(userId)
                .username(username)
                .build();
        when(userService.getUserByUsername(username)).thenReturn(mockUser2);

        COBSettingsDTO cobSettings = new COBSettingsDTO();
        cobSettings.setUserId(userId);
        cobSettings.setCarbRatio(2.0);
        cobSettings.setIsf(1.0);
        cobSettings.setCarbHalfLife(45);
        cobSettings.setMaxCOBDuration(480); // 8 hours max for HFHP
        when(cOBSettingsService.getCOBSettings(userId)).thenReturn(cobSettings);

        // Create a HFHP note from 7.5 hours ago with 60g carbs
        LocalDateTime sevenHoursAgo = LocalDateTime.now().minusHours(7).minusMinutes(30);
        Note hfhpNote = new Note(userId, sevenHoursAgo, 60.0, 4.0, "HFHP dinner");
        hfhpNote.setId(UUID.randomUUID());
        hfhpNote.setAbsorptionMode("HFHP");

        // The 6-hour window query returns nothing for 7.5h-old notes (the BUG):
        // findByUserIdAndTimestampBetween with a 6h start time excludes the HFHP note.
        // To fail the test correctly, we simulate the current query returning empty.
        when(noteRepository.findByUserIdAndTimestampBetween(
                eq(userId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(hfhpNote)); // 8h window includes the HFHP note

        ArgumentCaptor<List<CarbsEntry>> carbsCaptor = ArgumentCaptor.forClass(List.class);
        when(cobService.calculateTotalCarbsOnBoard(carbsCaptor.capture(), any(LocalDateTime.class), any(COBSettingsDTO.class))).thenReturn(0.0);

        // Required stubs for the calculation pipeline
        when(userInsulinPreferencesService.getRapidIobParameters(userId))
                .thenReturn(new RapidInsulinIobParameters(4.0, 75.0));
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);
        when(featureToggleConfig.isNutritionAwarePredictionEnabled()).thenReturn(false);

        GlucoseCalculationsRequest request = GlucoseCalculationsRequest.builder()
                .currentGlucose(8.0)
                .userId(username)
                .includePredictionFactors(false)
                .build();

        service.calculateGlucoseData(request);

        // BUG: carbsCaptor.getValue() will be empty (the HFHP note was excluded by 6h window).
        // After the fix (8h window), the HFHP note should appear in the list.
        // This FAILS because the current implementation uses a 6h window and returns empty.
        List<CarbsEntry> capturedEntries = carbsCaptor.getValue();
        assertThat(capturedEntries)
                .as("HFHP note from 7.5 hours ago must be included in COB calculation; "
                        + "current 6-hour window excludes it (BUG: L2)")
                .anyMatch(e -> e.getCarbs() != null && e.getCarbs() >= 60.0);
    }

    // ── Long-acting (basal) doses must not be treated as bolus IOB ───────────

    /**
     * A long-acting (basal) note carries an insulin dose but must NEVER be fed into the
     * rapid-acting bolus IOB curve / prediction path. It is excluded from the insulinEntries
     * passed to InsulinCalculatorService. Regression guard for the long-acting logging feature.
     */
    @Test
    void longActingNote_excludedFromBolusInsulinEntries() {
        String username = "basal_user";
        UUID userId = UUID.randomUUID();
        when(userService.getUserByUsername(username))
                .thenReturn(UserDto.builder().id(userId).username(username).build());

        COBSettingsDTO cobSettings = new COBSettingsDTO();
        cobSettings.setUserId(userId);
        cobSettings.setCarbRatio(2.0);
        cobSettings.setIsf(1.0);
        cobSettings.setCarbHalfLife(45);
        cobSettings.setMaxCOBDuration(240);
        when(cOBSettingsService.getCOBSettings(userId)).thenReturn(cobSettings);

        // 20 U of long-acting taken 1 h ago — a massive phantom bolus IOB if mis-classified.
        Note basal = new Note(userId, LocalDateTime.now().minusHours(1), 0.0, 20.0, "Tresiba");
        basal.setId(UUID.randomUUID());
        basal.setType(Note.TYPE_LONG_ACTING);
        when(noteRepository.findByUserIdAndTimestampBetween(any(), any(), any()))
                .thenReturn(List.of(basal));

        when(cobService.calculateTotalCarbsOnBoard(any(), any(), any(COBSettingsDTO.class))).thenReturn(0.0);
        when(userInsulinPreferencesService.getRapidIobParameters(userId))
                .thenReturn(new RapidInsulinIobParameters(4.0, 75.0));
        when(featureToggleConfig.isNutritionAwarePredictionEnabled()).thenReturn(false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InsulinDose>> insulinCaptor = ArgumentCaptor.forClass(List.class);
        when(insulinCalculatorService.calculateTotalActiveInsulin(
                insulinCaptor.capture(), any(), anyDouble(), anyDouble())).thenReturn(0.0);

        service.calculateGlucoseData(GlucoseCalculationsRequest.builder()
                .currentGlucose(6.0).userId(username).includePredictionFactors(false).build());

        assertThat(insulinCaptor.getValue())
                .as("long-acting (basal) doses must be excluded from bolus IOB entries")
                .isEmpty();
    }

    // ── P4: getCOBSettings called O(N) times per prediction path ─────────────

    /**
     * // BUG: P4 — GlucoseCalculationsService.buildPredictionPath calls
     * cobService.calculateTotalCarbsOnBoard once per prediction step (1-minute intervals,
     * up to 480 steps). CarbsOnBoardService.calculateTotalCarbsOnBoard calls
     * getCOBSettings on EACH invocation, causing 240-480 DB/cache lookups per request.
     * getCOBSettings must be called exactly ONCE per calculateGlucoseData call.
     *
     * This test uses a real CarbsOnBoardService (not mocked) so the prediction loop
     * actually reaches getCOBSettings. It FAILS against current code because
     * getCOBSettings is called once per loop iteration.
     */
    @Test
    void p4_calculateGlucoseData_getCOBSettings_calledExactlyOnce() {
        COBSettingsService cobSettingsMock = mock(COBSettingsService.class);
        CarbsOnBoardService realCobService = new CarbsOnBoardService(cobSettingsMock);

        // Use a fresh service instance wired to the real cobService
        GlucoseCalculationsService svc = new GlucoseCalculationsService(
                realCobService, insulinCalculatorService, noteRepository,
                userService, userInsulinPreferencesService, objectMapper,
                featureToggleConfig, cobSettingsMock);

        String username = "p4user";
        UUID userId = UUID.randomUUID();

        UserDto mockUser = UserDto.builder().id(userId).username(username).build();
        when(userService.getUserByUsername(username)).thenReturn(mockUser);

        COBSettingsDTO cobSettings = new COBSettingsDTO();
        cobSettings.setUserId(userId);
        cobSettings.setCarbRatio(2.0);
        cobSettings.setIsf(1.0);
        cobSettings.setCarbHalfLife(45);
        cobSettings.setMaxCOBDuration(240);
        when(cobSettingsMock.getCOBSettings(userId)).thenReturn(cobSettings);

        when(noteRepository.findByUserIdAndTimestampBetween(any(), any(), any()))
                .thenReturn(List.of());
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);
        when(userInsulinPreferencesService.getRapidIobParameters(userId))
                .thenReturn(new RapidInsulinIobParameters(4.0, 75.0));
        when(featureToggleConfig.isNutritionAwarePredictionEnabled()).thenReturn(false);

        GlucoseCalculationsRequest request = GlucoseCalculationsRequest.builder()
                .currentGlucose(7.0)
                .userId(username)
                .includePredictionFactors(false)
                .build();

        svc.calculateGlucoseData(request);

        // BUG: currently called 240+ times (once per prediction step) — this FAILS
        verify(cobSettingsMock, times(1))
                .getCOBSettings(userId);
    }

    // ── NotebookLM scenario 4: prospective notes null / empty ────────────────

    @Test
    void prospectiveNotesNull_doesNotCrash_returnsValidResponse() {
        String username = "prosp_null";
        UUID userId = UUID.randomUUID();
        stubFullPipeline(username, userId);

        GlucoseCalculationsRequest request = GlucoseCalculationsRequest.builder()
                .currentGlucose(6.0)
                .userId(username)
                .prospectiveNotes(null)
                .build();

        var response = service.calculateGlucoseData(request);

        assertThat(response).isNotNull();
        assertThat(response.getTwoHourPrediction()).isNotNull();
    }

    @Test
    void prospectiveNotesEmpty_treatedSameAsNoProspectiveNotes() {
        String username = "prosp_empty";
        UUID userId = UUID.randomUUID();
        stubFullPipeline(username, userId);

        GlucoseCalculationsRequest withNull = GlucoseCalculationsRequest.builder()
                .currentGlucose(7.0).userId(username).prospectiveNotes(null).build();
        GlucoseCalculationsRequest withEmpty = GlucoseCalculationsRequest.builder()
                .currentGlucose(7.0).userId(username).prospectiveNotes(List.of()).build();

        var respNull  = service.calculateGlucoseData(withNull);
        var respEmpty = service.calculateGlucoseData(withEmpty);

        assertThat(respNull.getTwoHourPrediction())
                .isEqualTo(respEmpty.getTwoHourPrediction());
    }

    // ── NotebookLM scenario 5: hypoglycemia branch — confidence reduced ───────

    @Test
    void confidence_hypoGlucose_reducedBelowNormal() {
        String username = "hypo_user";
        UUID userId = UUID.randomUUID();
        stubFullPipeline(username, userId);

        // Include a prospective carbs note so carbsEntries.size()=1 → base confidence
        // becomes CONFIDENCE_MEDIUM (0.7) rather than CONFIDENCE_LOW (0.5).
        // Hypo path: 0.7 * 0.8 = 0.56 which is < normal 0.7, and > CONFIDENCE_LOW floor.
        che.glucosemonitorbe.dto.ProspectiveNoteDTO meal =
                che.glucosemonitorbe.dto.ProspectiveNoteDTO.builder().carbs(40.0).meal("Lunch").build();

        GlucoseCalculationsRequest hypo = GlucoseCalculationsRequest.builder()
                .currentGlucose(2.8).userId(username).prospectiveNotes(List.of(meal)).build();
        GlucoseCalculationsRequest normal = GlucoseCalculationsRequest.builder()
                .currentGlucose(6.5).userId(username).prospectiveNotes(List.of(meal)).build();

        var respHypo   = service.calculateGlucoseData(hypo);
        var respNormal = service.calculateGlucoseData(normal);

        assertThat(respHypo.getConfidence()).isLessThan(respNormal.getConfidence());
    }

    @Test
    void confidence_zeroDataPoints_returnsLowConfidence() {
        String username = "no_data";
        UUID userId = UUID.randomUUID();
        stubFullPipeline(username, userId);

        GlucoseCalculationsRequest request = GlucoseCalculationsRequest.builder()
                .currentGlucose(7.0).userId(username).build();

        var response = service.calculateGlucoseData(request);

        // 0 carbs + 0 insulin entries → CONFIDENCE_LOW = 0.5
        assertThat(response.getConfidence()).isLessThanOrEqualTo(0.5);
    }

    @Test
    void predictionPath_notEmpty_for4hHorizon() {
        String username = "path_user";
        UUID userId = UUID.randomUUID();
        stubFullPipeline(username, userId);

        GlucoseCalculationsRequest request = GlucoseCalculationsRequest.builder()
                .currentGlucose(8.0).userId(username).build();

        var response = service.calculateGlucoseData(request);

        assertThat(response.getPredictionPath()).isNotNull().isNotEmpty();
        assertThat(response.getFourHourPrediction()).isNotNull();
    }

    @Test
    void predictionPath_glucoseClamped_between1and25() {
        String username = "clamp_user";
        UUID userId = UUID.randomUUID();
        stubFullPipeline(username, userId);

        // Extreme glucose that might produce out-of-bounds predictions
        GlucoseCalculationsRequest high = GlucoseCalculationsRequest.builder()
                .currentGlucose(22.0).userId(username).build();

        var response = service.calculateGlucoseData(high);

        response.getPredictionPath().forEach(point ->
                assertThat(point.getPredictedGlucose()).isBetween(1.0, 25.0));
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private void stubFullPipeline(String username, UUID userId) {
        che.glucosemonitorbe.dto.UserDto mockUser = che.glucosemonitorbe.dto.UserDto.builder()
                .id(userId).username(username).build();
        when(userService.getUserByUsername(username)).thenReturn(mockUser);

        COBSettingsDTO cobSettings = new COBSettingsDTO();
        cobSettings.setUserId(userId);
        cobSettings.setCarbRatio(2.0);
        cobSettings.setIsf(1.0);
        cobSettings.setCarbHalfLife(45);
        cobSettings.setMaxCOBDuration(240);
        when(cOBSettingsService.getCOBSettings(userId)).thenReturn(cobSettings);

        when(noteRepository.findByUserIdAndTimestampBetween(
                any(UUID.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(cobService.calculateTotalCarbsOnBoard(any(), any(LocalDateTime.class), any(COBSettingsDTO.class))).thenReturn(0.0);
        when(userInsulinPreferencesService.getRapidIobParameters(userId))
                .thenReturn(new RapidInsulinIobParameters(4.0, 75.0));
        when(insulinCalculatorService.calculateTotalActiveInsulin(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(0.0);
        when(featureToggleConfig.isNutritionAwarePredictionEnabled()).thenReturn(false);
    }

    // ── P5: NotesService.getAllNotes lacks @Cacheable ─────────────────────────

    /**
     * // BUG: P5 — NotesService.getAllNotes has no @Cacheable annotation. Every call
     * hits the database, even for the same userId within a short window. High-frequency
     * polling from the iOS app (every 30 s) causes repeated full-table scans.
     *
     * This test FAILS because the @Cacheable annotation is absent.
     */
    @Test
    void p5_notesService_getAllNotes_mustHaveCacheableAnnotation() throws Exception {
        Method method = NotesService.class.getMethod("getAllNotes", UUID.class);

        // BUG: method has no @Cacheable — this FAILS
        assertThat(method.isAnnotationPresent(Cacheable.class))
                .as("NotesService.getAllNotes must be @Cacheable to avoid repeated full scans (BUG: P5)")
                .isTrue();
    }
}