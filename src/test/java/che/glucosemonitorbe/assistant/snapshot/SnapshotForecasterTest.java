package che.glucosemonitorbe.assistant.snapshot;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.assistant.snapshot.SnapshotForecaster.FrozenForecast;
import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.PredictionEngine;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.entity.UserDigitalTwin;
import che.glucosemonitorbe.repository.UserDigitalTwinRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnapshotForecasterTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Mock GlucoseCalculationsService calculations;
    @Mock UserService userService;
    @Mock UserSettingsService userSettingsService;
    @Mock FeatureToggleConfig toggles;
    @Mock UserDigitalTwinRepository twins;
    @Mock CgmFreshnessService cgm;

    private SnapshotForecaster forecaster;
    private final UserSettingsDTO settings = new UserSettingsDTO();

    @BeforeEach
    void setUp() {
        forecaster = new SnapshotForecaster(calculations, userService, userSettingsService, toggles, twins, cgm);
        User user = new User();
        user.setUsername("vlad");
        when(userService.getUserById(USER)).thenReturn(user);
        when(userSettingsService.getUserSettings(USER)).thenReturn(settings);
        when(twins.findByUserId(USER)).thenReturn(Optional.empty());
        List<PredictionPointDTO> path = new ArrayList<>();
        for (int m = 5; m <= 480; m += 5) {
            PredictionPointDTO p = new PredictionPointDTO();
            p.setTimestamp(NOON.plusMinutes(m));
            p.setPredictedGlucose(7.0 + m / 1000.0);
            path.add(p);
        }
        GlucoseCalculationsResponse resp = new GlucoseCalculationsResponse();
        resp.setPredictionPath(path);
        when(calculations.calculateGlucoseData(any())).thenReturn(resp);
    }

    @Test
    void usesDashboardForecastFromFreshCgmAsOfSaveMoment() {
        when(cgm.freshReading(eq(USER), any())).thenReturn(Optional.of(new CgmReadingDTO(6.5, NOON, 0, "Flat")));

        FrozenForecast f = forecaster.forecast(USER, NOON, 9.9).orElseThrow();

        ArgumentCaptor<GlucoseCalculationsRequest> req = ArgumentCaptor.forClass(GlucoseCalculationsRequest.class);
        verify(calculations).calculateGlucoseData(req.capture());
        assertThat(req.getValue().getUserId()).isEqualTo("vlad");
        assertThat(req.getValue().getCurrentGlucose()).isEqualTo(6.5);
        assertThat(req.getValue().getClientTimeInfo().toLocalDateTime()).isEqualTo(NOON);
        assertThat(req.getValue().getClientTimeInfo().getTimezone()).isNull();
        assertThat(f.startGlucose()).isEqualTo(6.5);
        assertThat(f.engine()).isEqualTo("hovorka");
        assertThat(f.modelVersion()).isNull();
        assertThat(f.points()).hasSize(48);
        assertThat(f.points().get(0).minute()).isEqualTo(5);
        assertThat(f.points().get(47).minute()).isEqualTo(240);
    }

    @Test
    void fallsBackToNoteGlucoseWithoutFreshCgm() {
        when(cgm.freshReading(eq(USER), any())).thenReturn(Optional.empty());

        assertThat(forecaster.forecast(USER, NOON, 9.9).orElseThrow().startGlucose()).isEqualTo(9.9);
    }

    @Test
    void noGlucoseAtAllMeansNoForecast() {
        when(cgm.freshReading(eq(USER), any())).thenReturn(Optional.empty());

        assertThat(forecaster.forecast(USER, NOON, null)).isEmpty();
        verify(calculations, never()).calculateGlucoseData(any());
    }

    @Test
    void recordsEmpiricalEngineAndAppliedTwinVersion() {
        when(cgm.freshReading(eq(USER), any())).thenReturn(Optional.of(new CgmReadingDTO(6.5, NOON, 0, "Flat")));
        settings.setPredictionEngine(PredictionEngine.EMPIRICAL);
        when(toggles.isEmpiricalTwinEnabled()).thenReturn(true);
        assertThat(forecaster.forecast(USER, NOON, null).orElseThrow().engine()).isEqualTo("empirical");

        settings.setPredictionEngine(PredictionEngine.EMPIRICAL);
        when(toggles.isEmpiricalTwinEnabled()).thenReturn(false);
        UserDigitalTwin twin = new UserDigitalTwin();
        twin.setApplied(true);
        twin.setFittedAt(LocalDateTime.of(2026, 10, 30, 3, 0));
        when(twins.findByUserId(USER)).thenReturn(Optional.of(twin));
        FrozenForecast f = forecaster.forecast(USER, NOON, null).orElseThrow();
        assertThat(f.engine()).isEqualTo("hovorka");
        assertThat(f.modelVersion()).isEqualTo("twin@2026-10-30T03:00");
    }
}
