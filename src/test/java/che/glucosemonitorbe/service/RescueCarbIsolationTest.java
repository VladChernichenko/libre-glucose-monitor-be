package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.IsfMealWindowSnapshot;
import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.VerificationEvent;
import che.glucosemonitorbe.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.*;

/**
 * A rescue carb (fast glucose taken to treat a hypo) has no bolus behind it and no meal behind
 * it. Two self-tuning loops read every note indiscriminately and would otherwise be corrupted by
 * one:
 * <ul>
 *   <li>{@link VerificationService} scores predicted-vs-actual glucose after a note to titrate
 *   carbRatio - scoring a rescue would feed unbolused carbs into that loop.</li>
 *   <li>{@link IsfMealWindowProfileService} sums nearby carbs against {@code CARB_THRESHOLD_GRAMS}
 *   to decide whether a bolus is a correction (weight 1.0) or meal-attached (weight 0.4), and
 *   subtracts the expected meal rise from the observed CGM delta. A rescue crossing that
 *   threshold would both reclassify an unrelated correction bolus and subtract a glucose rise
 *   that never came from a meal.</li>
 * </ul>
 */
class RescueCarbIsolationTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID NOTE_ID = UUID.randomUUID();

    // -- VerificationService: rescue notes must never enter the titration loop --------------

    @Test
    void verificationSkipsHypoTreatmentNotes() {
        VerificationEventRepository verificationEventRepository = mock(VerificationEventRepository.class);
        VerificationSummaryRepository verificationSummaryRepository = mock(VerificationSummaryRepository.class);
        NoteRepository noteRepository = mock(NoteRepository.class);
        UserSettingsRepository userSettingsRepository = mock(UserSettingsRepository.class);
        CgmReadingRepository cgmReadingRepository = mock(CgmReadingRepository.class);
        VerificationService verificationService = new VerificationService(
                verificationEventRepository, verificationSummaryRepository,
                noteRepository, userSettingsRepository, cgmReadingRepository);

        Note rescue = new Note(USER_ID, LocalDateTime.now(), 15.0, 0.0, "Hypo treatment");
        rescue.setType(Note.TYPE_HYPO_TREATMENT);
        rescue.setId(NOTE_ID);
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(rescue));
        when(verificationEventRepository.findByNoteId(NOTE_ID)).thenReturn(Optional.empty());
        when(verificationEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        verificationService.enqueueNote(NOTE_ID, USER_ID);

        ArgumentCaptor<VerificationEvent> saved = ArgumentCaptor.forClass(VerificationEvent.class);
        verify(verificationEventRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(VerificationEvent.Status.SKIPPED);
        assertThat(saved.getValue().getSkipReason()).isEqualTo("hypo_treatment");
    }

    // -- IsfMealWindowProfileService: a rescue near a correction bolus must be inert --------

    /**
     * Differential test: an identical 7-correction-bolus scenario is run twice, once with a
     * 15 g rescue note logged 20 minutes after one of the boluses (inside its carb-window),
     * once without. If the rescue were counted, it would cross
     * {@code IsfMealWindowProfileService.CARB_THRESHOLD_GRAMS} (10.0 g) on its own, reclassifying
     * that bolus from a correction (weight 1.0) to meal-attached (weight 0.4) AND subtracting a
     * phantom meal rise from its ISF estimate - corrupting both the weighted-sample mass and the
     * ISF value for the whole bucket. Asserting the two runs produce IDENTICAL results proves the
     * rescue is genuinely inert, not just "some number came out".
     */
    @Test
    void isfEstimateIgnoresRescueCarbsNearACorrectionBolus() {
        UUID userId = UUID.randomUUID();

        IsfMealWindowSnapshot baseline = runIsfScenario(userId, false);
        IsfMealWindowSnapshot withRescue = runIsfScenario(userId, true);

        // Sanity: the baseline itself hits the reporting threshold with WEIGHT_CORRECTION for
        // all 7 events, so the comparison below is between two meaningful (non-null) values.
        assertThat(baseline.getWeightedSamples()).isEqualTo(7.0);
        assertThat(baseline.getIsfMmolPerU()).isCloseTo(2.5, offset(0.02));

        assertThat(withRescue.getWeightedSamples()).isEqualTo(baseline.getWeightedSamples());
        assertThat(withRescue.getIsfMmolPerU()).isEqualTo(baseline.getIsfMmolPerU());
        assertThat(withRescue.getRawSampleCount()).isEqualTo(baseline.getRawSampleCount());
    }

    /**
     * Builds and runs a fresh, fully-mocked {@link IsfMealWindowProfileService} instance over
     * 7 correction boluses (2u each, CGM 9.0 -> 4.0 mmol/L over the 4.5h DIA, all BREAKFAST),
     * optionally injecting a 15 g rescue note 20 minutes after the first bolus, and returns the
     * BREAKFAST snapshot that gets saved. A fresh service + mocks per call keeps the two runs of
     * the differential test fully independent.
     */
    private IsfMealWindowSnapshot runIsfScenario(UUID userId, boolean includeRescue) {
        NoteRepository noteRepository = mock(NoteRepository.class);
        CgmReadingRepository cgmReadingRepository = mock(CgmReadingRepository.class);
        UserInsulinPreferencesService userInsulinPreferencesService = mock(UserInsulinPreferencesService.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        CarbsOnBoardService carbsOnBoardService = mock(CarbsOnBoardService.class);
        IsfMealWindowSnapshotRepository snapshotRepository = mock(IsfMealWindowSnapshotRepository.class);

        IsfMealWindowProfileService service = new IsfMealWindowProfileService(
                noteRepository, cgmReadingRepository, userInsulinPreferencesService,
                userSettingsService, carbsOnBoardService, snapshotRepository);

        RapidInsulinIobParameters rapid = new RapidInsulinIobParameters(4.5, 75.0);
        UserSettingsDTO userSettings = new UserSettingsDTO();
        userSettings.setCarbRatio(2.0);
        userSettings.setIsf(2.5);
        userSettings.setCarbHalfLife(45);
        userSettings.setMaxCOBDuration(240);

        when(userInsulinPreferencesService.getRapidIobParameters(userId)).thenReturn(rapid);
        when(userSettingsService.getUserSettings(userId)).thenReturn(userSettings);
        when(snapshotRepository.findByUserIdAndMealWindow(eq(userId), any())).thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // All carbs fully absorbed by tEnd, matching IsfMealWindowProfileServiceTest's default.
        when(carbsOnBoardService.calculateTotalCarbsOnBoard(any(), any(), any(UserSettingsDTO.class)))
                .thenReturn(0.0);

        LocalDateTime anchor = LocalDateTime.now().minusDays(1)
                .withHour(7).withMinute(30).withSecond(0).withNano(0);
        long diaMinutes = (long) (rapid.diaHours() * 60);

        List<Note> notes = new ArrayList<>();
        List<CgmReading> cgm = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            LocalDateTime t = anchor.minusDays(day);
            notes.add(correctionBolus(userId, t, 2.0));
            cgm.add(cgmReading(userId, t, 9.0));
            cgm.add(cgmReading(userId, t.plusMinutes(diaMinutes), 4.0));
        }

        if (includeRescue) {
            // Inside the first bolus's carb-window ([-30min, +DIA]) but not any other bolus's -
            // boluses are 24h apart, so only day 0's window can see it.
            LocalDateTime rescueTime = anchor.plusMinutes(20);
            Note rescue = new Note(userId, rescueTime, 15.0, 0.0, "Hypo treatment");
            rescue.setId(UUID.randomUUID());
            rescue.setType(Note.TYPE_HYPO_TREATMENT);
            notes.add(rescue);
        }

        when(noteRepository.findByUserIdAndTimestampBetween(eq(userId), any(), any())).thenReturn(notes);
        when(cgmReadingRepository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(eq(userId), any()))
                .thenReturn(cgm);

        service.recomputeForUser(userId);

        ArgumentCaptor<IsfMealWindowSnapshot> captor = ArgumentCaptor.forClass(IsfMealWindowSnapshot.class);
        verify(snapshotRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
                .filter(s -> s.getMealWindow() == MealWindow.BREAKFAST)
                .reduce((a, b) -> b) // last saved wins
                .orElseThrow(() -> new AssertionError("No BREAKFAST snapshot saved"));
    }

    private Note correctionBolus(UUID userId, LocalDateTime ts, double units) {
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

    private CgmReading cgmReading(UUID userId, LocalDateTime ts, double mmol) {
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
}
