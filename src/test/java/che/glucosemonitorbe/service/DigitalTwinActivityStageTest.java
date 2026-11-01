package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.ActivityGains;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator.ActivityFit;
import che.glucosemonitorbe.hovorka.learning.PredictionUncertaintyModel;
import che.glucosemonitorbe.hovorka.learning.ResidualBiasModel;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserDigitalTwinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The activity stage of {@link DigitalTwinCalibrationService#calibrateUser}, with the fitter mocked. */
class DigitalTwinActivityStageTest {

    private static final UUID USER = UUID.randomUUID();
    private static final int READINGS = 250;          // train = first 200 (TRAIN_FRACTION 0.8)
    private static final long STEP_MS = 5 * 60_000L;

    private final CgmReadingRepository cgmRepo = mock(CgmReadingRepository.class);
    private final NoteRepository noteRepo = mock(NoteRepository.class);
    private final UserDigitalTwinRepository twinRepo = mock(UserDigitalTwinRepository.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final FeatureToggleConfig toggles = mock(FeatureToggleConfig.class);
    private final DigitalTwinCalibrator fitter = mock(DigitalTwinCalibrator.class);

    private DigitalTwinCalibrationService calibration;
    private long firstMs;
    private long trainEndMs;

    @BeforeEach
    void setUp() {
        when(toggles.isDigitalTwinEnabled()).thenReturn(true);
        when(toggles.isActivityLoggingEnabled()).thenReturn(true);
        when(twinRepo.findByUserId(USER)).thenReturn(Optional.empty());
        HovorkaParameterService params = mock(HovorkaParameterService.class);
        double w = HovorkaParameters.DEFAULT_WEIGHT;
        when(params.buildRawForUser(USER)).thenReturn(new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * w, HovorkaParameters.F01_PER_KG * w, HovorkaParameters.F01_PER_KG * w,
                HovorkaParameters.EGP0_PER_KG * w, HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G, 1.0, 2.2, w));
        calibration = new DigitalTwinCalibrationService(cgmRepo, noteRepo, null, twinRepo, null,
                params, null, null, null,
                mock(UserInsulinPreferencesService.class), settings, mock(DigitalTwinService.class), toggles);
        calibration.calibratorFactory = () -> fitter;

        // 250 readings, 5 min apart, ending one step before now (the user's zone is UTC: no settings).
        long lastMs = Instant.now().toEpochMilli() - STEP_MS;
        firstMs = lastMs - (READINGS - 1) * STEP_MS;
        trainEndMs = firstMs + 199 * STEP_MS;
        List<CgmReading> readings = new ArrayList<>();
        for (int i = 0; i < READINGS; i++) {
            CgmReading r = new CgmReading();
            r.setSgv(110);
            r.setDateTimestamp(firstMs + i * STEP_MS);
            readings.add(r);
        }
        when(cgmRepo.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(eq(USER), anyLong()))
                .thenReturn(readings);
        when(fitter.fitActivity(any(), any(), any(), anyInt()))
                .thenReturn(new ActivityFit(ActivityGains.NEUTRAL, false, 1.0, 1.0, 0, "stub"));
    }

    private static LocalDateTime wall(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    private static Note activity(long epochMs) {
        Note n = new Note();
        n.setTimestamp(wall(epochMs));
        n.setType(Note.TYPE_ACTIVITY);
        n.setDurationMin(30);
        n.setIntensity("MODERATE");
        n.setCarbs(0.0);
        n.setInsulin(0.0);
        return n;
    }

    private static Note meal(long epochMs) {
        Note n = new Note();
        n.setTimestamp(wall(epochMs));
        n.setCarbs(40.0);
        n.setInsulin(4.0);
        return n;
    }

    private void notesAre(Note... notes) {
        when(noteRepo.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of(notes));
    }

    private void twinImproved(boolean improved, TwinScales scales) {
        when(fitter.calibrate(any(), any())).thenReturn(new DigitalTwinCalibrator.Result(scales,
                ResidualBiasModel.neutral(), PredictionUncertaintyModel.populationDefault(), 1.0,
                improved ? 0.8 : 1.0, 100, 25, improved, "LOW", "status"));
    }

    @Test
    void activityCountCountsOnlyActivityNotesInTrainingWindow() {
        twinImproved(true, TwinScales.neutral());
        notesAre(activity(firstMs + 10 * STEP_MS), activity(firstMs + 100 * STEP_MS), activity(trainEndMs),
                meal(firstMs + 50 * STEP_MS),                 // not activity
                activity(trainEndMs + 10 * STEP_MS));         // validation window
        calibration.calibrateUser(USER);
        verify(fitter).fitActivity(any(), any(), any(), eq(3));
    }

    @Test
    void stageSkippedWhenActivityProviderIsNone() {
        twinImproved(true, TwinScales.neutral());
        when(toggles.isActivityLoggingEnabled()).thenReturn(false);
        notesAre(activity(firstMs + 10 * STEP_MS));
        calibration.calibrateUser(USER);
        verify(fitter, never()).fitActivity(any(), any(), any(), anyInt());
    }

    @Test
    void stageSkippedWhenNoUsableActivityNotes() {
        twinImproved(true, TwinScales.neutral());
        notesAre(meal(firstMs + 10 * STEP_MS));
        calibration.calibrateUser(USER);
        verify(fitter, never()).fitActivity(any(), any(), any(), anyInt());
    }

    @Test
    void twinNotImproved_fitsAgainstNeutralScales() {
        twinImproved(false, TwinScales.of(1.4, 1.2));
        notesAre(activity(firstMs + 10 * STEP_MS));
        calibration.calibrateUser(USER);
        verify(fitter).fitActivity(any(), any(), eq(TwinScales.neutral()), anyInt());
    }

    @Test
    void twinImproved_fitsAgainstFittedScales() {
        TwinScales fitted = TwinScales.of(1.4, 1.2);
        twinImproved(true, fitted);
        notesAre(activity(firstMs + 10 * STEP_MS));
        calibration.calibrateUser(USER);
        verify(fitter).fitActivity(any(), any(), eq(fitted), anyInt());
    }
}
