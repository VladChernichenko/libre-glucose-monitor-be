package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.entity.UserDigitalTwin;
import che.glucosemonitorbe.hovorka.AbsorptionModel;
import che.glucosemonitorbe.hovorka.ActivityGains;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator.ActivityFit;
import che.glucosemonitorbe.hovorka.learning.PredictionUncertaintyModel;
import che.glucosemonitorbe.hovorka.learning.ResidualBiasModel;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.repository.UserDigitalTwinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A twin is fitted against one meal-absorption model (Dalla Man or Gamma). Applied to the other its
 * meal scale and residual grid correct errors that model does not have, so it must stay inactive
 * until it is refitted under the model the forecast runs.
 */
class DigitalTwinAbsorptionModelGuardTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 12, 0);

    private UserDigitalTwinRepository repo;
    private FeatureToggleConfig toggles;
    private DigitalTwinService twinService;
    private DigitalTwinCalibrationService calibration;

    @BeforeEach
    void setUp() {
        repo = mock(UserDigitalTwinRepository.class);
        toggles = mock(FeatureToggleConfig.class);
        when(toggles.isDigitalTwinEnabled()).thenReturn(true);
        twinService = new DigitalTwinService(repo, toggles);
        calibration = new DigitalTwinCalibrationService(null, null, null, repo, null, null, null, null, null,
                null, null, null, twinService, toggles);
    }

    private static UserDigitalTwin appliedTwin(String absorptionModel) {
        return UserDigitalTwin.builder().userId(USER).applied(true).agScale(1.26)
                .residualGrid("0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5")
                .activityApplied(true).activityGainScale(1.5)
                .absorptionModel(absorptionModel).build();
    }

    private void stored(UserDigitalTwin twin) {
        when(repo.findByUserId(USER)).thenReturn(Optional.of(twin));
        twinService.invalidate(USER);
    }

    @Test
    void persistsTheAbsorptionModelTheTwinWasFittedUnder() {
        when(repo.findByUserId(USER)).thenReturn(Optional.empty());
        when(toggles.isGammaAbsorptionEnabled()).thenReturn(true);
        DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator.Result(TwinScales.neutral(),
                ResidualBiasModel.neutral(), PredictionUncertaintyModel.populationDefault(),
                1.0, 0.8, 100, 25, true, "LOW", "status");
        calibration.persist(USER, r, new ActivityFit(ActivityGains.NEUTRAL, false, 1, 1, 0, "no"), NOW);
        ArgumentCaptor<UserDigitalTwin> saved = ArgumentCaptor.forClass(UserDigitalTwin.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getAbsorptionModel()).isEqualTo("GAMMA");

        when(toggles.isGammaAbsorptionEnabled()).thenReturn(false);
        calibration.persist(USER, r, null, NOW);
        verify(repo, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getValue().getAbsorptionModel()).isEqualTo("DALLA_MAN");
    }

    @Test
    void twinFittedUnderOtherAbsorptionModelIsNotApplied() {
        when(toggles.isGammaAbsorptionEnabled()).thenReturn(true);
        stored(appliedTwin("DALLA_MAN"));

        assertThat(twinService.activeScales(USER)).isEmpty();
        assertThat(twinService.activeResidual(USER).isNeutral()).isTrue();
        assertThat(twinService.activeUncertainty(USER).sdAtHorizon(60))
                .isEqualTo(PredictionUncertaintyModel.populationDefault().sdAtHorizon(60));
        assertThat(twinService.activeActivityGains(USER)).isEmpty();
    }

    @Test
    void aTwinWithNoRecordedModelCountsAsDallaMan() {
        stored(appliedTwin(null));

        when(toggles.isGammaAbsorptionEnabled()).thenReturn(false);
        assertThat(twinService.activeScales(USER)).isPresent();

        when(toggles.isGammaAbsorptionEnabled()).thenReturn(true);
        twinService.invalidate(USER);
        assertThat(twinService.activeScales(USER)).isEmpty();
    }

    @Test
    void twinFittedUnderTheCurrentModelIsApplied() {
        when(toggles.isGammaAbsorptionEnabled()).thenReturn(true);
        stored(appliedTwin("GAMMA"));

        assertThat(twinService.activeScales(USER)).hasValueSatisfying(s -> assertThat(s.agScale()).isEqualTo(1.26));
        assertThat(twinService.activeActivityGains(USER)).isPresent();
    }

    @Test
    void modelNamesRoundTrip() {
        assertThat(AbsorptionModel.parse(null)).isEqualTo(AbsorptionModel.DALLA_MAN);
        assertThat(AbsorptionModel.parse("GAMMA")).isEqualTo(AbsorptionModel.GAMMA);
        assertThat(AbsorptionModel.parse("junk")).isEqualTo(AbsorptionModel.DALLA_MAN);
        assertThat(AbsorptionModel.of(true)).isEqualTo(AbsorptionModel.GAMMA);
        assertThat(AbsorptionModel.of(false)).isEqualTo(AbsorptionModel.DALLA_MAN);
    }
}
