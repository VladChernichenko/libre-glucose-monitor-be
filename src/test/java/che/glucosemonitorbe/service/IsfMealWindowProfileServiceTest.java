package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.IsfMealWindowSnapshot;
import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.dto.IsfMealWindowDTO;
import che.glucosemonitorbe.dto.IsfMealWindowProfileResponse;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.IsfMealWindowSnapshotRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IsfMealWindowProfileService}. Covers:
 * <ul>
 *   <li>Meal-window classification (incl. NIGHT)</li>
 *   <li>Per-event ISF deconvolution algebra (correction vs meal weighting)</li>
 *   <li>Edge cases: stacked boluses, missing CGM, implausible values, long-acting filter</li>
 *   <li>Bucket threshold + DTO {@code hasData} mapping</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IsfMealWindowProfileServiceTest {

    @Mock private NoteRepository noteRepository;
    @Mock private CgmReadingRepository cgmReadingRepository;
    @Mock private UserInsulinPreferencesService userInsulinPreferencesService;
    @Mock private UserSettingsService userSettingsService;
    @Mock private InsulinCalculatorService insulinCalculatorService;
    @Mock private CarbsOnBoardService carbsOnBoardService;
    @Mock private IsfMealWindowSnapshotRepository snapshotRepository;

    private IsfMealWindowProfileService service;

    private final UUID userId = UUID.randomUUID();

    // CR = 2.0 mmol/L per 10g, ISF target = 2.5 mmol/L per unit, DIA = 4.5h, peak = 75min
    private static final RapidInsulinIobParameters RAPID = new RapidInsulinIobParameters(4.5, 75.0);
    private static final UserSettingsDTO USER_SETTINGS = userSettings(2.0, 2.5, 45, 240);

    @BeforeEach
    void setUp() {
        service = new IsfMealWindowProfileService(
                noteRepository, cgmReadingRepository,
                userInsulinPreferencesService, userSettingsService,
                carbsOnBoardService, snapshotRepository);

        when(userInsulinPreferencesService.getRapidIobParameters(userId)).thenReturn(RAPID);
        when(userSettingsService.getUserSettings(userId)).thenReturn(USER_SETTINGS);
        when(snapshotRepository.findByUserIdAndMealWindow(eq(userId), any())).thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // By default, all carbs fully absorbed by end of window
        when(carbsOnBoardService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class)))
                .thenReturn(0.0);
    }

    // -- MealWindow classification ---------------------------------------------

    @Nested
    @DisplayName("MealWindow.fromHour")
    class WindowClassification {

        @Test
        @DisplayName("Hours 5-10 map to BREAKFAST")
        void breakfast() {
            for (int h = 5; h <= 10; h++) {
                assertThat(MealWindow.fromHour(h)).hasValue(MealWindow.BREAKFAST);
            }
        }

        @Test
        @DisplayName("Hours 11-15 map to LUNCH")
        void lunch() {
            for (int h = 11; h <= 15; h++) {
                assertThat(MealWindow.fromHour(h)).hasValue(MealWindow.LUNCH);
            }
        }

        @Test
        @DisplayName("Hours 16-21 map to DINNER")
        void dinner() {
            for (int h = 16; h <= 21; h++) {
                assertThat(MealWindow.fromHour(h)).hasValue(MealWindow.DINNER);
            }
        }

        @Test
        @DisplayName("Night hours 22, 23, 0-4 map to NIGHT")
        void night() {
            for (int h : new int[]{22, 23, 0, 1, 2, 3, 4}) {
                assertThat(MealWindow.fromHour(h)).as("hour=%d", h).hasValue(MealWindow.NIGHT);
            }
        }

        @Test
        @DisplayName("Out-of-range hours return empty")
        void invalidHours() {
            assertThat(MealWindow.fromHour(-1)).isEmpty();
            assertThat(MealWindow.fromHour(24)).isEmpty();
            assertThat(MealWindow.fromTimestamp(null)).isEmpty();
        }
    }

    // -- Empty-data behaviour --------------------------------------------------

    @Test
    @DisplayName("Empty data -> 4 buckets returned, all hasData=false, isfMmolPerU=null")
    void emptyData_fourBucketsAllEmpty() {
        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any()))
                .thenReturn(List.of());
        when(cgmReadingRepository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(eq(userId), any()))
                .thenReturn(List.of());
        when(snapshotRepository.findByUserId(userId)).thenReturn(List.of());

        IsfMealWindowProfileResponse response = service.recomputeForUser(userId);

        assertThat(response.getWindows()).hasSize(4);
        assertThat(response.getWindows()).extracting(IsfMealWindowDTO::getMealWindow)
                .containsExactly("BREAKFAST", "LUNCH", "DINNER", "NIGHT");
        assertThat(response.getWindows()).allMatch(w -> !w.isHasData());
        assertThat(response.getWindows()).allMatch(w -> w.getIsfMmolPerU() == null);
        assertThat(response.getHistoryDays()).isEqualTo(14);
        assertThat(response.getMinWeightedSamples())
                .isEqualTo(IsfMealWindowProfileService.MIN_WEIGHTED_SAMPLES);
    }

    // -- Pure correction bolus, no carbs ---------------------------------------

    @Test
    @DisplayName("Pure correction bolus with no carbs: ISF = ΔCGM / units, weight = 1.0")
    void correctionBolus_isfFromObservedDrop() {
        // Bolus 2u at 07:30 (BREAKFAST), CGM 9.0 -> 4.0 mmol/L over 4.5h -> drop = 5.0 mmol
        // No carbs -> Δcarb = 0 -> insulinAttributedDrop = 0 − (4.0 − 9.0) = 5.0
        // ISF = 5.0 / 2 = 2.5 mmol/L per unit
        LocalDateTime t = today(7, 30);
        Note bolus = bolus(t, 2.0);
        List<Note> notes = List.of(bolus);
        List<CgmReading> cgm = List.of(
                cgmReading(t, 9.0),
                cgmReading(t.plusMinutes((long)(RAPID.diaHours() * 60)), 4.0));

        stub(notes, cgm);

        service.recomputeForUser(userId);

        IsfMealWindowSnapshot saved = captureSavedSnapshot(MealWindow.BREAKFAST);
        // Only 1 sample, weight=1.0 - below threshold (7.0), so isfMmolPerU is null
        assertThat(saved.getRawSampleCount()).isEqualTo(1);
        assertThat(saved.getWeightedSamples()).isEqualTo(1.0);
        assertThat(saved.getIsfMmolPerU()).isNull();
    }

    @Test
    @DisplayName("7 correction boluses all at ISF=2.5 -> bucket reports 2.5 (threshold met)")
    void sevenCorrectionBoluses_thresholdMet() {
        // 7 identical correction events at 7am on consecutive days, each yielding ISF=2.5
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            LocalDateTime t = today(7, 30).minusDays(day);
            notes.add(bolus(t, 2.0));
            cgm.add(cgmReading(t, 9.0));
            cgm.add(cgmReading(t.plusMinutes((long)(RAPID.diaHours() * 60)), 4.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        IsfMealWindowSnapshot saved = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(saved.getRawSampleCount()).isEqualTo(7);
        assertThat(saved.getWeightedSamples()).isEqualTo(7.0);
        // CGM stored as int mg/dL - 9.0 mmol round-trips as 8.99, etc. Hence ~0.01 mmol slop per
        // event, ~0.005 mmol/L/u per ISF estimate. Tolerance reflects that.
        assertThat(saved.getIsfMmolPerU()).isCloseTo(2.5, offset(0.02));
    }

    // -- Meal-attached bolus ---------------------------------------------------

    @Test
    @DisplayName("Meal-attached bolus uses 0.4 weight and deconvolves carbs via CR")
    void mealBolus_weightedLower_andCarbsDeconvolved() {
        // Bolus 4u + 40g carbs at 13:00 (LUNCH)
        // Carbs absorbed fully -> Δcarb = (40/10) × CR(2.0) = 8.0 mmol/L
        // CGM 8.0 -> 6.0  -> observedΔ = −2.0
        // insulinAttributedDrop = 8.0 − (−2.0) = 10.0
        // ISF = 10.0 / 4 = 2.5
        LocalDateTime t = today(13, 0);
        Note bolus = bolusWithCarbs(t, 4.0, 40.0);
        List<Note> notes = List.of(bolus);
        List<CgmReading> cgm = List.of(
                cgmReading(t, 8.0),
                cgmReading(t.plusMinutes((long)(RAPID.diaHours() * 60)), 6.0));
        stub(notes, cgm);

        service.recomputeForUser(userId);

        IsfMealWindowSnapshot saved = captureSavedSnapshot(MealWindow.LUNCH);
        assertThat(saved.getRawSampleCount()).isEqualTo(1);
        assertThat(saved.getWeightedSamples()).isEqualTo(0.4); // meal-bolus weight
    }

    // -- Night exclusion -------------------------------------------------------

    @Test
    @DisplayName("Bolus at 02:00 is bucketed into NIGHT")
    void nightBolus_bucketedIntoNight() {
        LocalDateTime t = today(2, 0);
        Note bolus = bolus(t, 2.0);
        List<CgmReading> cgm = List.of(
                cgmReading(t, 9.0),
                cgmReading(t.plusMinutes((long)(RAPID.diaHours() * 60)), 4.0));
        stub(List.of(bolus), cgm);

        service.recomputeForUser(userId);

        IsfMealWindowSnapshot night = captureSavedSnapshot(MealWindow.NIGHT);
        assertThat(night.getRawSampleCount()).isEqualTo(1);
        assertThat(night.getWeightedSamples()).isEqualTo(1.0);
        assertThat(night.getIsfMmolPerU()).isNull(); // below MIN_WEIGHTED_SAMPLES

        for (MealWindow w : List.of(MealWindow.BREAKFAST, MealWindow.LUNCH, MealWindow.DINNER)) {
            IsfMealWindowSnapshot saved = captureSavedSnapshot(w);
            assertThat(saved.getRawSampleCount()).as("window=%s", w).isZero();
        }
    }

    // -- Long-acting exclusion -------------------------------------------------

    @Test
    @DisplayName("Long-acting basal note is excluded from ISF computation")
    void longActing_excluded() {
        LocalDateTime t = today(7, 0);
        Note basal = bolus(t, 10.0);
        basal.setType(Note.TYPE_LONG_ACTING);
        stub(List.of(basal), List.of(cgmReading(t, 9.0)));

        service.recomputeForUser(userId);

        IsfMealWindowSnapshot saved = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(saved.getRawSampleCount()).isZero();
    }

    // -- Stacked boluses -------------------------------------------------------

    @Test
    @DisplayName("A second bolus within the 4.5h DIA window invalidates the first")
    void stackedBoluses_firstDropped() {
        LocalDateTime t = today(7, 0);
        Note a = bolus(t, 2.0);
        Note b = bolus(t.plusHours(2), 1.0); // inside DIA window
        List<CgmReading> cgm = List.of(
                cgmReading(t, 9.0),
                cgmReading(t.plusMinutes((long)(RAPID.diaHours() * 60)), 4.0),
                cgmReading(t.plusHours(2), 7.0),
                cgmReading(t.plusHours(2).plusMinutes((long)(RAPID.diaHours() * 60)), 5.0));
        stub(List.of(a, b), cgm);

        service.recomputeForUser(userId);

        // First bolus dropped (stacked); second is at 09:00 (still BREAKFAST), its DIA ends at 13:30 (LUNCH)
        IsfMealWindowSnapshot saved = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(saved.getRawSampleCount()).isEqualTo(1); // only the second bolus
    }

    // -- Missing CGM -----------------------------------------------------------

    @Test
    @DisplayName("Bolus with no CGM coverage is dropped silently")
    void noCgm_eventDropped() {
        LocalDateTime t = today(7, 0);
        stub(List.of(bolus(t, 2.0)), List.of()); // no CGM at all

        service.recomputeForUser(userId);

        IsfMealWindowSnapshot saved = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(saved.getRawSampleCount()).isZero();
    }

    // -- Implausible ISF clamping ----------------------------------------------

    @Test
    @DisplayName("Negative ISF (glucose rose during a correction bolus) is dropped as implausible")
    void implausibleNegative_dropped() {
        // 2u correction at 7am, CGM rose from 5.0 to 8.0 - would yield ISF = -1.5
        LocalDateTime t = today(7, 0);
        stub(
                List.of(bolus(t, 2.0)),
                List.of(cgmReading(t, 5.0),
                        cgmReading(t.plusMinutes((long)(RAPID.diaHours() * 60)), 8.0)));

        service.recomputeForUser(userId);

        IsfMealWindowSnapshot saved = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(saved.getRawSampleCount()).isZero();
    }

    @Test
    @DisplayName("ISF > 10 mmol/L/u (physiologically absurd) is dropped")
    void implausibleHigh_dropped() {
        // 0.5u correction, CGM dropped 8 mmol/L -> ISF = 16 -> dropped
        LocalDateTime t = today(7, 0);
        stub(
                List.of(bolus(t, 0.5)),
                List.of(cgmReading(t, 12.0),
                        cgmReading(t.plusMinutes((long)(RAPID.diaHours() * 60)), 4.0)));

        service.recomputeForUser(userId);

        IsfMealWindowSnapshot saved = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(saved.getRawSampleCount()).isZero();
    }

    // -- sgvToMmol conversion --------------------------------------------------

    @Test
    @DisplayName("sgvToMmol: 100 mg/dL -> 5.55 mmol/L")
    void sgvConversion() {
        assertThat(IsfMealWindowProfileService.sgvToMmol(100)).isCloseTo(5.55, offset(0.01));
        assertThat(IsfMealWindowProfileService.sgvToMmol(180)).isCloseTo(9.99, offset(0.01));
    }

    // -- getProfile read path --------------------------------------------------

    @Test
    @DisplayName("getProfile returns all 4 buckets in canonical order, even if DB has only some")
    void getProfile_fillsMissingBucketsWithEmpty() {
        IsfMealWindowSnapshot lunchSnap = IsfMealWindowSnapshot.builder()
                .userId(userId)
                .mealWindow(MealWindow.LUNCH)
                .isfMmolPerU(2.4)
                .weightedSamples(8.5)
                .rawSampleCount(12)
                .lastUpdated(LocalDateTime.now())
                .build();
        when(snapshotRepository.findByUserId(userId)).thenReturn(List.of(lunchSnap));

        IsfMealWindowProfileResponse response = service.getProfile(userId);

        assertThat(response.getWindows()).hasSize(4);
        assertThat(response.getWindows().get(0).getMealWindow()).isEqualTo("BREAKFAST");
        assertThat(response.getWindows().get(0).isHasData()).isFalse();
        assertThat(response.getWindows().get(1).getMealWindow()).isEqualTo("LUNCH");
        assertThat(response.getWindows().get(1).isHasData()).isTrue();
        assertThat(response.getWindows().get(1).getIsfMmolPerU()).isEqualTo(2.4);
        assertThat(response.getWindows().get(2).getMealWindow()).isEqualTo("DINNER");
        assertThat(response.getWindows().get(2).isHasData()).isFalse();
        assertThat(response.getWindows().get(3).getMealWindow()).isEqualTo("NIGHT");
        assertThat(response.getWindows().get(3).isHasData()).isFalse();
    }

    // ---
    // Test helpers
    // ---

    /** A representative "today" anchor that varies the date but pins specific clock times. */
    private LocalDateTime today(int hour, int minute) {
        return LocalDateTime.now()
                .minusDays(1)
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }

    private Note bolus(LocalDateTime ts, double units) {
        Note n = new Note();
        n.setId(UUID.randomUUID());
        n.setUserId(userId);
        n.setTimestamp(ts);
        n.setInsulin(units);
        n.setCarbs(0.0);
        n.setMeal("Correction");
        n.setType(Note.TYPE_NORMAL);
        return n;
    }

    private Note bolusWithCarbs(LocalDateTime ts, double units, double carbs) {
        Note n = bolus(ts, units);
        n.setCarbs(carbs);
        n.setMeal("Lunch");
        return n;
    }

    private CgmReading cgmReading(LocalDateTime ts, double mmol) {
        // sgv (mg/dL) ≈ mmol × 18.0182
        int sgv = (int) Math.round(mmol * 18.0182);
        return CgmReading.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .dataSource(CgmReading.DataSource.NIGHTSCOUT)
                .sgv(sgv)
                .dateTimestamp(ts.toInstant(ZoneOffset.UTC).toEpochMilli())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private void stub(List<Note> notes, List<CgmReading> cgm) {
        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any()))
                .thenReturn(notes);
        when(cgmReadingRepository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(eq(userId), any()))
                .thenReturn(cgm);
    }

    private IsfMealWindowSnapshot captureSavedSnapshot(MealWindow window) {
        ArgumentCaptor<IsfMealWindowSnapshot> cap = ArgumentCaptor.forClass(IsfMealWindowSnapshot.class);
        org.mockito.Mockito.verify(snapshotRepository, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        return cap.getAllValues().stream()
                .filter(s -> s.getMealWindow() == window)
                .reduce((a, b) -> b) // last saved wins
                .orElseThrow(() -> new AssertionError("No snapshot saved for " + window));
    }

    private static UserSettingsDTO userSettings(double cr, double isf, int halfLife, int maxDuration) {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setCarbRatio(cr);
        s.setIsf(isf);
        s.setCarbHalfLife(halfLife);
        s.setMaxCOBDuration(maxDuration);
        return s;
    }
}
