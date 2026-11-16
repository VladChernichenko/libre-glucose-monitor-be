package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.learning.AnchorSampleSource;
import che.glucosemonitorbe.hovorka.learning.CurveScale;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.PredictionUncertaintyModel;
import che.glucosemonitorbe.hovorka.learning.ResidualBiasModel;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserDigitalTwinRepository;
import che.glucosemonitorbe.repository.UserInsulinCurveRepository;
import che.glucosemonitorbe.sick.SickProfile;
import che.glucosemonitorbe.sick.SickSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sick time (and its 6 h tail) must never reach the twin fit; replays run on healthy settings. */
class DigitalTwinSickExclusionTest {

    private static final UUID USER = UUID.randomUUID();
    private static final long STEP_MS = 5 * 60_000L;
    private static final int READINGS = 600;

    private final CgmReadingRepository cgmRepo = mock(CgmReadingRepository.class);
    private final NoteRepository noteRepo = mock(NoteRepository.class);
    private final UserDigitalTwinRepository twinRepo = mock(UserDigitalTwinRepository.class);
    private final UserSettingsService settingsService = mock(UserSettingsService.class);
    private final UserInsulinPreferencesService insulinPrefs = mock(UserInsulinPreferencesService.class);
    private final FeatureToggleConfig toggles = mock(FeatureToggleConfig.class);
    private final DigitalTwinCalibrator fitter = mock(DigitalTwinCalibrator.class);

    private DigitalTwinCalibrationService calibration;
    private long firstMs;

    @BeforeEach
    void setUp() {
        when(toggles.isDigitalTwinEnabled()).thenReturn(true);
        when(toggles.isPersonalInsulinCurveEnabled()).thenReturn(true);
        when(twinRepo.findByUserId(USER)).thenReturn(Optional.empty());
        HovorkaParameterService params = mock(HovorkaParameterService.class);
        double w = HovorkaParameters.DEFAULT_WEIGHT;
        when(params.buildRawForUser(USER)).thenReturn(new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * w, HovorkaParameters.F01_PER_KG * w, HovorkaParameters.F01_PER_KG * w,
                HovorkaParameters.EGP0_PER_KG * w, HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G, 1.0, 2.2, w));
        when(insulinPrefs.currentRapidCode(USER)).thenReturn("TEST");
        when(insulinPrefs.getCatalogRapidIobParameters(USER)).thenReturn(new RapidInsulinIobParameters(4.5, 75.0));
        calibration = new DigitalTwinCalibrationService(cgmRepo, noteRepo, null, twinRepo,
                mock(UserInsulinCurveRepository.class), null, params, null, null, null,
                insulinPrefs, settingsService, mock(DigitalTwinService.class), toggles);
        calibration.calibratorFactory = () -> fitter;

        long lastMs = Instant.now().toEpochMilli() - STEP_MS;
        firstMs = lastMs - (READINGS - 1) * STEP_MS;
        List<CgmReading> readings = new ArrayList<>();
        for (int i = 0; i < READINGS; i++) {
            CgmReading r = new CgmReading();
            r.setSgv(110);
            r.setDateTimestamp(firstMs + i * STEP_MS);
            readings.add(r);
        }
        when(cgmRepo.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(eq(USER), anyLong()))
                .thenReturn(readings);
        when(noteRepo.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of());
        when(fitter.calibrate(any(), any())).thenReturn(new DigitalTwinCalibrator.Result(TwinScales.neutral(),
                ResidualBiasModel.neutral(), PredictionUncertaintyModel.populationDefault(), 1.0,
                1.0, 100, 25, false, "LOW", "status"));
        when(fitter.fitInsulinCurve(any(), any(), any(), anyString(), anyInt()))
                .thenReturn(new DigitalTwinCalibrator.CurveFit(CurveScale.NEUTRAL, false, Double.NaN, Double.NaN, 0, "stub"));
    }

    private static LocalDateTime wall(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    private UserSettingsDTO settingsWith(SickProfile profile) {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setSickProfile(profile);
        return s;
    }

    /** A sick span over readings [from, to] (already ended, so the tail runs 6 h past {@code to}). */
    private SickProfile sickBetween(int fromIdx, int toIdx) {
        LocalDateTime from = wall(firstMs + fromIdx * STEP_MS);
        LocalDateTime to = wall(firstMs + toIdx * STEP_MS);
        return new SickProfile(List.of(new SickProfile.Span(from, to, to, SickSeverity.MODERATE)), null);
    }

    private int anchorsReachingTheFit() {
        ArgumentCaptor<AnchorSampleSource> train = ArgumentCaptor.forClass(AnchorSampleSource.class);
        ArgumentCaptor<AnchorSampleSource> val = ArgumentCaptor.forClass(AnchorSampleSource.class);
        verify(fitter).calibrate(train.capture(), val.capture());
        return train.getValue().anchorCount() + val.getValue().anchorCount();
    }

    @Test
    void readingsInsideASickEpisodeAndItsSixHourTailAreNotFitted() {
        when(settingsService.getUserSettings(USER)).thenReturn(settingsWith(SickProfile.EMPTY));
        calibration.calibrateUser(USER);
        int healthyAnchors = anchorsReachingTheFit();

        clearInvocations(fitter);
        when(settingsService.getUserSettings(USER)).thenReturn(settingsWith(sickBetween(200, 220)));
        calibration.calibrateUser(USER);
        int sickAnchors = anchorsReachingTheFit();

        assertThat(sickAnchors).isLessThan(healthyAnchors);
    }

    @Test
    void calibrationIsSkippedWhenSickTimeLeavesTooLittleData() {
        // 600 readings; sick from reading 100 to 500 plus the tail leaves well under 200 healthy readings.
        when(settingsService.getUserSettings(USER)).thenReturn(settingsWith(sickBetween(100, 500)));

        assertThat(calibration.calibrateUser(USER)).isNull();

        verify(fitter, never()).calibrate(any(), any());
    }

    @Test
    void replayRunsOnHealthySettings() {
        UserSettingsDTO sickSettings = settingsWith(sickBetween(200, 220));
        when(settingsService.getUserSettings(USER)).thenReturn(sickSettings);

        calibration.calibrateUser(USER);

        ArgumentCaptor<AnchorSampleSource> train = ArgumentCaptor.forClass(AnchorSampleSource.class);
        ArgumentCaptor<AnchorSampleSource> val = ArgumentCaptor.forClass(AnchorSampleSource.class);
        verify(fitter).calibrate(train.capture(), val.capture());
        ArgumentCaptor<AnchorSampleSource> curveTrain = ArgumentCaptor.forClass(AnchorSampleSource.class);
        ArgumentCaptor<AnchorSampleSource> curveVal = ArgumentCaptor.forClass(AnchorSampleSource.class);
        verify(fitter).fitInsulinCurve(curveTrain.capture(), curveVal.capture(), any(), anyString(), anyInt());

        for (AnchorSampleSource engine : List.of(train.getValue(), val.getValue(),
                curveTrain.getValue(), curveVal.getValue())) {
            UserSettingsDTO used = ((PredictionReplayEngine) engine).settings();
            assertThat(used.getSickProfile()).isEqualTo(SickProfile.EMPTY);
        }
        // The shared (cached) settings instance is never mutated.
        assertThat(sickSettings.getSickProfile().spans()).hasSize(1);
    }
}
