package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.entity.UserDigitalTwin;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DigitalTwinActivityPersistenceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    private UserDigitalTwinRepository repo;
    private FeatureToggleConfig toggles;
    private DigitalTwinCalibrationService calibration;
    private DigitalTwinService twinService;

    @BeforeEach
    void setUp() {
        repo = mock(UserDigitalTwinRepository.class);
        toggles = mock(FeatureToggleConfig.class);
        when(toggles.isDigitalTwinEnabled()).thenReturn(true);
        twinService = new DigitalTwinService(repo, toggles);
        calibration = new DigitalTwinCalibrationService(null, null, null, repo, null, null, null, null,
                null, null, null, twinService, toggles);
        when(repo.findByUserId(USER)).thenReturn(Optional.empty());
    }

    private static DigitalTwinCalibrator.Result result(boolean improved) {
        return new DigitalTwinCalibrator.Result(TwinScales.neutral(), ResidualBiasModel.neutral(),
                PredictionUncertaintyModel.populationDefault(), 1.0, improved ? 0.8 : 1.0,
                100, 25, improved, "LOW", "status");
    }

    private UserDigitalTwin persisted(boolean twinImproved, ActivityFit fit) {
        calibration.persist(USER, result(twinImproved), fit, NOW);
        ArgumentCaptor<UserDigitalTwin> c = ArgumentCaptor.forClass(UserDigitalTwin.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    private static ActivityFit appliedFit() {
        return new ActivityFit(new ActivityGains(1.8, 200), true, 1.0, 0.8, 7, "ok");
    }

    @Test
    void persistsActivityFit() {
        UserDigitalTwin t = persisted(true, appliedFit());
        assertThat(t.getActivityGainScale()).isEqualTo(1.8);
        assertThat(t.getActivityTailHalfLifeMin()).isEqualTo(200.0);
        assertThat(t.getActivityApplied()).isTrue();
        assertThat(t.getActivitySamples()).isEqualTo(7);
    }

    @Test
    void notAppliedFitIsStoredNeutral() {
        ActivityFit rejected = new ActivityFit(new ActivityGains(2.5, 300), false, 1.0, 1.0, 9, "no");
        UserDigitalTwin t = persisted(true, rejected);
        assertThat(t.getActivityApplied()).isFalse();
        assertThat(t.getActivityGainScale()).isEqualTo(1.0);
        assertThat(t.getActivityTailHalfLifeMin()).isEqualTo(ActivityGains.NEUTRAL.tailHalfLifeMin());
        assertThat(t.getActivitySamples()).isEqualTo(9);
    }

    @Test
    void skippedActivityStageStoresNeutral() {
        UserDigitalTwin t = persisted(false, null);
        assertThat(t.getActivityApplied()).isFalse();
        assertThat(t.getActivitySamples()).isZero();
    }

    @Test
    void activityAppliedIndependentOfTwinApplied() {
        UserDigitalTwin t = persisted(false, appliedFit());
        assertThat(t.getApplied()).isFalse();
        assertThat(t.getActivityApplied()).isTrue();

        when(repo.findByUserId(USER)).thenReturn(Optional.of(t));
        assertThat(twinService.activeScales(USER)).isEmpty();
        assertThat(twinService.activeActivityGains(USER)).contains(new ActivityGains(1.8, 200));
    }

    @Test
    void activeActivityGainsAbsentWhenNotAppliedOrToggledOff() {
        UserDigitalTwin t = UserDigitalTwin.builder().userId(USER).activityApplied(false)
                .activityGainScale(2.0).build();
        when(repo.findByUserId(USER)).thenReturn(Optional.of(t));
        assertThat(twinService.activeActivityGains(USER)).isEmpty();

        t.setActivityApplied(true);
        twinService.invalidate(USER);
        assertThat(twinService.activeActivityGains(USER)).isPresent();

        when(toggles.isDigitalTwinEnabled()).thenReturn(false);
        assertThat(twinService.activeActivityGains(USER)).isEmpty();
    }
}
