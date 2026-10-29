package che.glucosemonitorbe.service;

import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.LibreGlucoseData;
import che.glucosemonitorbe.dto.LibreGlucoseReading;
import che.glucosemonitorbe.dto.LibreUpstreamStatusDTO;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The per-user LibreLinkUp upstream circuit in {@link LibreLinkUpSyncService}: repeated failures open
 * it (calls then fail fast as UNAVAILABLE without touching LibreLinkUp), and {@code upstreamStatus}
 * reports LibreLinkUp unavailable from the first failure until a call succeeds again.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LibreLinkUpSyncServiceCircuitTest {

    @Mock private UserDataSourceConfigRepository configRepository;
    @Mock private LibreLinkUpService libreLinkUpService;
    @Mock private CgmReadingService cgmReadingService;
    @Mock private UserGlucoseSyncStateService syncStateService;
    @Spy private CircuitBreakerManager circuitBreakerManager = new CircuitBreakerManager();

    @InjectMocks private LibreLinkUpSyncService sut;

    private UUID userId;

    @BeforeEach
    void wire() {
        userId = UUID.randomUUID();
        ReflectionTestUtils.setField(sut, "fastIntervalMinutes", 5L);
        ReflectionTestUtils.setField(sut, "slowIntervalMinutes", 15L);
        when(syncStateService.getOrCreate(userId)).thenReturn(UserGlucoseSyncState.builder().build());
        when(configRepository.findByUserIdAndDataSourceAndIsActiveTrue(
                eq(userId), eq(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)))
                .thenReturn(Optional.of(libreConfig()));
        when(libreLinkUpService.isAuthenticated(userId)).thenReturn(true);
    }

    @Test
    @DisplayName("status is available before any failure")
    void status_initiallyAvailable() {
        LibreUpstreamStatusDTO status = sut.upstreamStatus(userId);

        assertThat(status.available()).isTrue();
        assertThat(status.state()).isEqualTo("CLOSED");
        assertThat(status.retryInSeconds()).isNull();
    }

    @Test
    @DisplayName("one failure reports LibreLinkUp unavailable while the circuit is still closed")
    void singleFailure_reportsUnavailable() throws Exception {
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenThrow(new RuntimeException("connect timed out"));

        assertThat(sut.syncUser(userId, false)).isEqualTo(LibreLinkUpSyncService.Outcome.ERROR);

        LibreUpstreamStatusDTO status = sut.upstreamStatus(userId);
        assertThat(status.available()).isFalse();
        assertThat(status.state()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("threshold failures open the circuit; further syncs fail fast without calling LibreLinkUp")
    void thresholdFailures_openCircuit_andFailFast() throws Exception {
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenThrow(new RuntimeException("503 Service Unavailable"));

        for (int i = 0; i < LibreLinkUpSyncService.UPSTREAM_FAILURE_THRESHOLD; i++) {
            assertThat(sut.syncUser(userId, false)).isEqualTo(LibreLinkUpSyncService.Outcome.ERROR);
        }

        assertThat(sut.syncUser(userId, false)).isEqualTo(LibreLinkUpSyncService.Outcome.UNAVAILABLE);
        verify(libreLinkUpService, times(LibreLinkUpSyncService.UPSTREAM_FAILURE_THRESHOLD))
                .getGlucoseData(any(), anyInt(), eq(userId));

        LibreUpstreamStatusDTO status = sut.upstreamStatus(userId);
        assertThat(status.available()).isFalse();
        assertThat(status.state()).isEqualTo("OPEN");
        assertThat(status.retryInSeconds()).isNotNull().isPositive()
                .isLessThanOrEqualTo(LibreLinkUpSyncService.UPSTREAM_OPEN_MS / 1000);
    }

    @Test
    @DisplayName("a success after a failure makes LibreLinkUp available again")
    void successAfterFailure_reportsAvailable() throws Exception {
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenThrow(new RuntimeException("connect timed out"))
                .thenReturn(libreData(6.4));

        sut.syncUser(userId, false);
        assertThat(sut.upstreamStatus(userId).available()).isFalse();

        assertThat(sut.syncUser(userId, false)).isEqualTo(LibreLinkUpSyncService.Outcome.NEW_DATA);
        assertThat(sut.upstreamStatus(userId).available()).isTrue();
    }

    @Test
    @DisplayName("a failed re-login counts as an upstream failure")
    void authFailure_countsAsUpstreamFailure() throws Exception {
        when(libreLinkUpService.isAuthenticated(userId)).thenReturn(false);
        when(libreLinkUpService.authenticate(any(), eq(userId)))
                .thenThrow(new RuntimeException("LibreLinkUp authentication failed: connect timed out"));

        assertThat(sut.syncUser(userId, false)).isEqualTo(LibreLinkUpSyncService.Outcome.ERROR);
        assertThat(sut.upstreamStatus(userId).available()).isFalse();
    }

    @Test
    @DisplayName("circuits are per user: one user's outage leaves another available")
    void circuitIsPerUser() throws Exception {
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenThrow(new RuntimeException("503 Service Unavailable"));
        for (int i = 0; i < LibreLinkUpSyncService.UPSTREAM_FAILURE_THRESHOLD; i++) {
            sut.syncUser(userId, false);
        }

        assertThat(sut.upstreamStatus(UUID.randomUUID()).available()).isTrue();
    }

    private UserDataSourceConfig libreConfig() {
        UserDataSourceConfig c = new UserDataSourceConfig();
        c.setDataSource(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP);
        c.setLibreEmail("user@example.com");
        c.setLibrePassword("pw");
        c.setLibrePatientId("patient-1");
        c.setLibreLocale("en-US");
        c.setIsActive(true);
        return c;
    }

    private LibreGlucoseData libreData(double... mmolValues) {
        LibreGlucoseData out = new LibreGlucoseData();
        out.setPatientId("patient-1");
        List<LibreGlucoseReading> readings = new java.util.ArrayList<>();
        long base = System.currentTimeMillis();
        for (int i = 0; i < mmolValues.length; i++) {
            LibreGlucoseReading r = new LibreGlucoseReading();
            r.setTimestamp(new Date(base + i * 60_000L));
            r.setValue(mmolValues[i]);
            r.setTrend(0);
            readings.add(r);
        }
        out.setData(readings);
        return out;
    }
}
