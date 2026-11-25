package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbRatioMealWindowSnapshot;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.dto.CarbRatioWindowEstimate;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CarbRatioMealWindowSnapshotRepository;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.sick.SickProfile;
import che.glucosemonitorbe.sick.SickSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CarbRatioMealWindowProfileService}: one ratio estimate per clean meal,
 * median per meal window once {@link CarbRatioMealWindowProfileService#MIN_MEALS} meals exist.
 * Unless a test says otherwise each scenario runs on 3 consecutive days, with CGM at the meal's
 * earliest bolus ({@code t0}) and at {@code t0 + DIA}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarbRatioMealWindowProfileServiceTest {

    @Mock private NoteRepository noteRepository;
    @Mock private CgmReadingRepository cgmReadingRepository;
    @Mock private UserInsulinPreferencesService userInsulinPreferencesService;
    @Mock private UserSettingsService userSettingsService;
    @Mock private CarbsOnBoardService carbsOnBoardService;
    @Mock private CarbRatioMealWindowSnapshotRepository snapshotRepository;

    private CarbRatioMealWindowProfileService service;

    private final UUID userId = UUID.randomUUID();

    // DIA 4.5 h, peak 75 min. ISF 2.5 mmol/L per unit, single ratio 10 g/U.
    private static final RapidInsulinIobParameters RAPID = new RapidInsulinIobParameters(4.5, 75.0);
    private static final long DIA_MIN = (long) (RAPID.diaHours() * 60);
    private static final UserSettingsDTO USER_SETTINGS = userSettings();
    private static final int DAYS = 3;

    @BeforeEach
    void setUp() {
        service = new CarbRatioMealWindowProfileService(
                noteRepository, cgmReadingRepository,
                userInsulinPreferencesService, userSettingsService,
                carbsOnBoardService, snapshotRepository);

        when(userInsulinPreferencesService.getRapidIobParameters(userId)).thenReturn(RAPID);
        when(userInsulinPreferencesService.rapidCurveByCode(userId)).thenReturn(code -> RAPID);
        when(userSettingsService.getUserSettings(userId)).thenReturn(USER_SETTINGS);
        when(snapshotRepository.findByUserIdAndMealWindow(eq(userId), any())).thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // All carbs absorbed by the end of every window.
        when(carbsOnBoardService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class)))
                .thenReturn(0.0);
    }

    @Test
    @DisplayName("Clean meal: ratio = carbs / (bolus + Δ / window ISF)")
    void cleanMeal_ratioIsCarbsOverUnitsNeeded() {
        // 60 g + 6 U, 7.0 -> 8.5 at ISF 2.5: 6 + 1.5 / 2.5 = 6.6 U needed -> 9.09 g/U
        stubDailyMeal(DAYS, 8, 0, 6.0, 60.0, 7.0, 8.5);

        service.recomputeForUser(userId);

        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(3);
        assertThat(breakfast.getGramsPerUnit()).isCloseTo(9.09, offset(0.05));
    }

    @Test
    @DisplayName("Fewer than three clean meals in a window: no ratio, count still reported")
    void fewerThanThreeMeals_noData() {
        stubDailyMeal(2, 8, 0, 6.0, 60.0, 7.0, 8.5);

        service.recomputeForUser(userId);

        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(2);
        assertThat(breakfast.getGramsPerUnit()).isNull();
    }

    @Test
    @DisplayName("The window value is the median of the meal estimates, not the mean")
    void medianNotMean() {
        // 48, 54, 120 g each with 6 U, flat: 8, 9, 20 g/U -> median 9 (mean would be 12.3)
        double[] carbs = {48.0, 54.0, 120.0};
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime t0 = day(d, 8, 0);
            notes.add(bolusWithCarbs(t0, 6.0, carbs[d]));
            cgm.addAll(cgmAround(t0, 7.0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(3);
        assertThat(breakfast.getGramsPerUnit()).isCloseTo(9.0, offset(1e-9));
    }

    @Test
    @DisplayName("A follow-up correction counts by the share of it that acted before the window ends")
    void followUpCorrectionIsCounted() {
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime t0 = day(d, 8, 0);
            notes.add(bolusWithCarbs(t0, 5.0, 60.0));
            notes.add(bolus(t0.plusHours(2), 1.0));
            cgm.addAll(cgmAround(t0, 7.0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        double expected = 60.0 / (5.0 + 1.0 - InsulinCalculatorService.iobOpenApsExponential(1.0, 150, 4.5, 75));
        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(3);
        assertThat(breakfast.getGramsPerUnit()).isCloseTo(expected, offset(1e-6));
    }

    @Test
    @DisplayName("Rescue carbs inside the window are counted, not a reason to drop the meal")
    void rescueCarbsAreCounted() {
        // 60 g + 8 U, rescue 15 g at 10:30, flat -> 75 / 8 = 9.375
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime t0 = day(d, 8, 0);
            notes.add(bolusWithCarbs(t0, 8.0, 60.0));
            notes.add(rescue(day(d, 10, 30), 15.0));
            cgm.addAll(cgmAround(t0, 7.0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(3);
        assertThat(breakfast.getGramsPerUnit()).isCloseTo(9.375, offset(1e-9));
    }

    @Test
    @DisplayName("Carbs still on board from an earlier meal count in; carbs left at the end count out")
    void priorAndRemainingCobAreCounted() {
        // 30 g + 3 U at 05:00 (START - 3 h: before the carb window, and skipped as its own event because
        // the 08:00 meal lands inside its insulin window), then 60 g + 6 U at 08:00, flat CGM.
        // COB of the 05:00 meal at 08:00 = 20 g; COB of both meals at 08:00 + DIA = 5 g.
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime start = day(d, 8, 0);
            LocalDateTime tEnd = start.plusMinutes(DIA_MIN);
            notes.add(bolusWithCarbs(start.minusHours(3), 3.0, 30.0));
            notes.add(bolusWithCarbs(start, 6.0, 60.0));
            cgm.addAll(cgmAround(start, 7.0, 7.0));
            when(carbsOnBoardService.calculateTotalCarbsOnBoard(
                    argThat(carbsOf(30.0)), eq(start), any(UserSettingsDTO.class))).thenReturn(20.0);
            when(carbsOnBoardService.calculateTotalCarbsOnBoard(
                    argThat(carbsOf(60.0, 30.0)), eq(tEnd), any(UserSettingsDTO.class))).thenReturn(5.0);
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        double priorIob = InsulinCalculatorService.iobOpenApsExponential(3.0, 180, 4.5, 75);
        double expected = (60.0 + 20.0 - 5.0) / (6.0 + priorIob);
        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(3);
        assertThat(breakfast.getGramsPerUnit()).isCloseTo(expected, offset(1e-6));
    }

    @Test
    @DisplayName("A split bolus around one meal is one event anchored at the earliest dose")
    void splitBolusIsOneMeal() {
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime t0 = day(d, 7, 50);
            notes.add(bolus(t0, 3.0));
            notes.add(meal(day(d, 8, 0), 60.0));
            notes.add(bolus(day(d, 8, 10), 3.0));
            cgm.addAll(cgmAround(t0, 7.0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(3);
        assertThat(breakfast.getGramsPerUnit()).isCloseTo(10.0, offset(0.2));
    }

    @Test
    @DisplayName("A pre-bolus logged on its own note anchors the meal that follows it")
    void preBolusLoggedSeparatelyAnchorsTheMeal() {
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime t0 = day(d, 7, 40);
            notes.add(bolus(t0, 6.0));
            notes.add(meal(day(d, 8, 0), 60.0));
            cgm.addAll(cgmAround(t0, 7.0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(3);
        assertThat(breakfast.getGramsPerUnit()).isCloseTo(10.0, offset(1e-9));
    }

    @Test
    @DisplayName("A bolus given after eating: the meal, not the bolus, is the CGM baseline")
    void bolusAfterTheMealUsesTheMealAsBaseline() {
        // 60 g at 08:00, 6 U at 08:20, glucose 7.0 -> 8.2 by 08:20 -> back to 7.0. The event starts at
        // the meal: baseline 7.0 at 08:00, end 08:00 + DIA, the 08:20 bolus counted by the share that
        // acted by then (250 of 270 min). Starting at the bolus instead would take 8.2 as the baseline
        // and learn 60 / (6 - 1.2 / 2.5) ≈ 10.9 (the 7.0 at 08:20 + DIA lets that wrong path finish).
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime mealAt = day(d, 8, 0);
            LocalDateTime bolusAt = day(d, 8, 20);
            notes.add(meal(mealAt, 60.0));
            notes.add(bolus(bolusAt, 6.0));
            cgm.add(cgmReading(mealAt, 7.0));
            cgm.add(cgmReading(bolusAt, 8.2));
            cgm.add(cgmReading(mealAt.plusMinutes(DIA_MIN), 7.0));
            cgm.add(cgmReading(bolusAt.plusMinutes(DIA_MIN), 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        double actedBolus = 6.0 - InsulinCalculatorService.iobOpenApsExponential(6.0, DIA_MIN - 20, 4.5, 75);
        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(3);
        assertThat(breakfast.getGramsPerUnit())
                .isCloseTo(60.0 / actedBolus, offset(0.05))
                .isNotCloseTo(10.9, offset(0.3));
    }

    @Test
    @DisplayName("One meal logged as two notes before one late bolus is one event")
    void mealLoggedAsTwoNotesBeforeOneLateBolusCountsOnce() {
        // 40 g at 08:00 and 20 g at 08:10, both bolused by 6 U at 08:20. The 08:00 note claims the
        // bolus and starts the event at 08:00; the 08:10 note shares that bolus, so it is a duplicate
        // and its 20 g count only through the 08:00 event's carb window.
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime start = day(d, 8, 0);
            notes.add(meal(start, 40.0));
            notes.add(meal(day(d, 8, 10), 20.0));
            notes.add(bolus(day(d, 8, 20), 6.0));
            cgm.addAll(cgmAround(start, 7.0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        double actedBolus = 6.0 - InsulinCalculatorService.iobOpenApsExponential(6.0, DIA_MIN - 20, 4.5, 75);
        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isEqualTo(3);
        assertThat(breakfast.getGramsPerUnit()).isCloseTo(60.0 / actedBolus, offset(1e-6));
    }

    @Test
    @DisplayName("A second meal inside the insulin window skips the first but is still its own event")
    void secondMealInsideTheWindowSkipsTheFirst() {
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime breakfast = day(d, 8, 0);
            LocalDateTime lunch = day(d, 11, 0);
            notes.add(bolusWithCarbs(breakfast, 6.0, 60.0));
            notes.add(bolusWithCarbs(lunch, 4.0, 40.0));
            cgm.addAll(cgmAround(breakfast, 7.0, 7.0));
            cgm.addAll(cgmAround(lunch, 7.0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        assertThat(captureSavedSnapshot(MealWindow.BREAKFAST).getSampleCount()).isZero();
        assertThat(captureSavedSnapshot(MealWindow.LUNCH).getSampleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("A meal after midnight is bucketed into NIGHT")
    void mealAfterMidnightBucketsIntoNight() {
        stubDailyMeal(DAYS, 0, 30, 3.0, 30.0, 7.0, 7.0);

        service.recomputeForUser(userId);

        CarbRatioMealWindowSnapshot night = captureSavedSnapshot(MealWindow.NIGHT);
        assertThat(night.getSampleCount()).isEqualTo(3);
        assertThat(night.getGramsPerUnit()).isCloseTo(10.0, offset(1e-9));
        for (MealWindow w : List.of(MealWindow.BREAKFAST, MealWindow.LUNCH, MealWindow.DINNER)) {
            assertThat(captureSavedSnapshot(w).getSampleCount()).as("window=%s", w).isZero();
        }
    }

    @Test
    @DisplayName("A meal with no bolus within 30 minutes is not an event")
    void unbolusedMeal_ignored() {
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime t0 = day(d, 8, 0);
            notes.add(meal(t0, 60.0));
            cgm.addAll(cgmAround(t0, 7.0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        for (MealWindow w : MealWindow.values()) {
            assertThat(captureSavedSnapshot(w).getSampleCount()).as("window=%s", w).isZero();
        }
    }

    @Test
    @DisplayName("Meals eaten while sick are not learned from")
    void sickPeriodSkipped() {
        LocalDateTime first = day(DAYS - 1, 8, 0);
        LocalDateTime last = day(0, 8, 0);
        UserSettingsDTO sick = USER_SETTINGS.withSickProfile(new SickProfile(List.of(
                new SickProfile.Span(first.minusHours(1), last.plusHours(1), last.plusHours(1),
                        SickSeverity.MODERATE)), null));
        when(userSettingsService.getUserSettings(userId)).thenReturn(sick);
        stubDailyMeal(DAYS, 8, 0, 6.0, 60.0, 7.0, 8.5);

        service.recomputeForUser(userId);

        assertThat(captureSavedSnapshot(MealWindow.BREAKFAST).getSampleCount()).isZero();
    }

    @Test
    @DisplayName("No CGM reading near the end of the window: the meal is skipped")
    void missingCgmSkipped() {
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime t0 = day(d, 8, 0);
            notes.add(bolusWithCarbs(t0, 6.0, 60.0));
            cgm.add(cgmReading(t0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        assertThat(captureSavedSnapshot(MealWindow.BREAKFAST).getSampleCount()).isZero();
    }

    @Test
    @DisplayName("A ratio outside 3-30 g/U is dropped")
    void outOfRangeRatioDropped() {
        // 60 g + 1.5 U, flat -> 40 g/U
        stubDailyMeal(DAYS, 8, 0, 1.5, 60.0, 7.0, 7.0);

        service.recomputeForUser(userId);

        CarbRatioMealWindowSnapshot breakfast = captureSavedSnapshot(MealWindow.BREAKFAST);
        assertThat(breakfast.getSampleCount()).isZero();
        assertThat(breakfast.getGramsPerUnit()).isNull();
    }

    @Test
    @DisplayName("Fewer than 0.5 U needed: the meal is dropped")
    void unitsNeededBelowHalfUnitDropped() {
        // 10 g + 1 U, 9.0 -> 7.5 at ISF 2.5: 1 - 0.6 = 0.4 U needed. 10 / 0.4 = 25 g/U is in range,
        // so only the 0.5 U gate can drop it.
        stubDailyMeal(DAYS, 8, 0, 1.0, 10.0, 9.0, 7.5);

        service.recomputeForUser(userId);

        assertThat(captureSavedSnapshot(MealWindow.BREAKFAST).getSampleCount()).isZero();
    }

    @Test
    @DisplayName("A long-acting note with carbs does not anchor a meal")
    void longActingIgnored() {
        // The rapid dose alone has no meal: only the long-acting note carries the carbs.
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDateTime t0 = day(d, 8, 0);
            Note basal = bolusWithCarbs(t0, 20.0, 60.0);
            basal.setType(Note.TYPE_LONG_ACTING);
            notes.add(basal);
            notes.add(bolus(t0, 6.0));
            cgm.addAll(cgmAround(t0, 7.0, 7.0));
        }
        stub(notes, cgm);

        service.recomputeForUser(userId);

        assertThat(captureSavedSnapshot(MealWindow.BREAKFAST).getSampleCount()).isZero();
    }

    @Test
    @DisplayName("getProfile returns all 4 windows in canonical order, filling missing rows")
    void getProfile_fillsMissingWindows() {
        CarbRatioMealWindowSnapshot lunch = CarbRatioMealWindowSnapshot.builder()
                .userId(userId)
                .mealWindow(MealWindow.LUNCH)
                .gramsPerUnit(11.0)
                .sampleCount(4)
                .computedAt(LocalDateTime.now())
                .build();
        when(snapshotRepository.findByUserId(userId)).thenReturn(List.of(lunch));

        List<CarbRatioWindowEstimate> profile = service.getProfile(userId);

        assertThat(profile).containsExactly(
                new CarbRatioWindowEstimate(MealWindow.BREAKFAST, null, 0),
                new CarbRatioWindowEstimate(MealWindow.LUNCH, 11.0, 4),
                new CarbRatioWindowEstimate(MealWindow.DINNER, null, 0),
                new CarbRatioWindowEstimate(MealWindow.NIGHT, null, 0));
    }

    // ---
    // Test helpers (copied from IsfMealWindowProfileServiceTest)
    // ---

    /** A representative "today" anchor that varies the date but pins specific clock times. */
    private LocalDateTime today(int hour, int minute) {
        return LocalDateTime.now()
                .minusDays(1)
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }

    /** {@link #today} moved {@code daysBack} days earlier. */
    private LocalDateTime day(int daysBack, int hour, int minute) {
        return today(hour, minute).minusDays(daysBack);
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

    /** Carbs with no insulin on the note. */
    private Note meal(LocalDateTime ts, double carbs) {
        Note n = bolusWithCarbs(ts, 0.0, carbs);
        n.setMeal("Breakfast");
        return n;
    }

    /** A rescue carb taken to treat a hypo. */
    private Note rescue(LocalDateTime ts, double carbs) {
        Note n = meal(ts, carbs);
        n.setMeal("Hypo");
        n.setType(Note.TYPE_HYPO_TREATMENT);
        return n;
    }

    private CgmReading cgmReading(LocalDateTime ts, double mmol) {
        // sgv (mg/dL) ≈ mmol × 18.0182. Epoch must match noteTimestampToEpochMs (JVM zone).
        int sgv = (int) Math.round(mmol * 18.0182);
        return CgmReading.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .dataSource(CgmReading.DataSource.NIGHTSCOUT)
                .sgv(sgv)
                .dateTimestamp(ts.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    /** Readings at {@code t0} and {@code t0 + DIA}. */
    private List<CgmReading> cgmAround(LocalDateTime t0, double atStart, double atEnd) {
        return List.of(cgmReading(t0, atStart), cgmReading(t0.plusMinutes(DIA_MIN), atEnd));
    }

    /** {@code units} with {@code carbs} on one note at hh:mm on each of {@code days} days, CGM {@code atStart} -> {@code atEnd}. */
    private void stubDailyMeal(int days, int hour, int minute, double units, double carbs, double atStart, double atEnd) {
        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            LocalDateTime t0 = day(d, hour, minute);
            notes.add(bolusWithCarbs(t0, units, carbs));
            cgm.addAll(cgmAround(t0, atStart, atEnd));
        }
        stub(notes, cgm);
    }

    private void stub(List<Note> notes, List<CgmReading> cgm) {
        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any()))
                .thenReturn(notes);
        when(cgmReadingRepository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(eq(userId), any()))
                .thenReturn(cgm);
    }

    /** Matches a COB call over exactly these carb entries, in this order. */
    private static ArgumentMatcher<List<CarbsEntry>> carbsOf(Double... grams) {
        return entries -> entries != null
                && entries.stream().map(CarbsEntry::getCarbs).toList().equals(List.of(grams));
    }

    private CarbRatioMealWindowSnapshot captureSavedSnapshot(MealWindow window) {
        ArgumentCaptor<CarbRatioMealWindowSnapshot> cap = ArgumentCaptor.forClass(CarbRatioMealWindowSnapshot.class);
        Mockito.verify(snapshotRepository, Mockito.atLeastOnce()).save(cap.capture());
        return cap.getAllValues().stream()
                .filter(s -> s.getMealWindow() == window)
                .reduce((a, b) -> b) // last saved wins
                .orElseThrow(() -> new AssertionError("No snapshot saved for " + window));
    }

    private static UserSettingsDTO userSettings() {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setCarbRatio(2.0);
        s.setGramsPerUnit(10.0);
        s.setIsf(2.5);
        s.setCarbHalfLife(45);
        s.setMaxCOBDuration(240);
        s.setTimezone(ZoneId.systemDefault().getId());
        return s;
    }
}
