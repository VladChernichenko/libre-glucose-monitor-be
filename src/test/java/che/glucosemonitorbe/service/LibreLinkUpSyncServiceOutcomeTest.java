package che.glucosemonitorbe.service;

import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.LibreGlucoseData;
import che.glucosemonitorbe.dto.LibreGlucoseReading;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.libre.LibreRateLimitedException;
import che.glucosemonitorbe.sync.SyncOutcome;
import che.glucosemonitorbe.sync.SyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** syncUser reports a {@link SyncResult} and never writes next_poll_at (the queue lane does). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LibreLinkUpSyncServiceOutcomeTest {

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
        when(syncStateService.getOrCreate(userId)).thenReturn(UserGlucoseSyncState.builder().build());
        UserDataSourceConfig c = new UserDataSourceConfig();
        c.setDataSource(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP);
        c.setLibreEmail("user@example.com");
        c.setLibrePassword("pw");
        c.setLibrePatientId("patient-1");
        c.setIsActive(true);
        when(configRepository.findByUserIdAndDataSourceAndIsActiveTrue(
                eq(userId), eq(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)))
                .thenReturn(Optional.of(c));
    }

    private LibreGlucoseData data(long ts) {
        LibreGlucoseReading r = new LibreGlucoseReading();
        r.setTimestamp(new Date(ts));
        r.setValue(6.4);
        r.setTrend(0);
        LibreGlucoseData out = new LibreGlucoseData();
        out.setPatientId("patient-1");
        out.setData(List.of(r));
        return out;
    }

    @Test
    void newData_returnsNewestTimestamp_andWritesNoNextPoll() throws Exception {
        long ts = 1_700_000_000_000L;
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId))).thenReturn(data(ts));

        SyncResult result = sut.syncUser(userId, false);

        assertThat(result.outcome()).isEqualTo(SyncOutcome.NEW_DATA);
        assertThat(result.newestTimestamp()).isEqualTo(ts);
        verify(syncStateService, never()).markNewData(any(), anyLong(), any(), any());
        verify(syncStateService, never()).markNoChange(any(), any(), any());
        verify(syncStateService, never()).markError(any(), any(), any());
    }

    @Test
    void http429_returnsRateLimited() throws Exception {
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId))).thenThrow(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        new HttpHeaders(), new byte[0], null));

        assertThat(sut.syncUser(userId, false).outcome()).isEqualTo(SyncOutcome.RATE_LIMITED);
        verify(syncStateService, never()).markError(any(), any(), any());
    }

    @Test
    void credentialsRejected_returnsSkippedNoCreds() throws Exception {
        doThrow(new che.glucosemonitorbe.service.libre.LibreCredentialsRejectedException("incorrect username/password"))
                .when(libreLinkUpService).ensureSession(userId);

        assertThat(sut.syncUser(userId, false).outcome()).isEqualTo(SyncOutcome.SKIPPED_NO_CREDS);
    }

    @Test
    void flaggedConfig_skipsWithoutCallingLibre() throws Exception {
        configRepository.findByUserIdAndDataSourceAndIsActiveTrue(userId, UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)
                .orElseThrow().setLibreCredentialsRejectedAt(java.time.Instant.now());

        assertThat(sut.syncUser(userId, false).outcome()).isEqualTo(SyncOutcome.SKIPPED_NO_CREDS);
        verify(libreLinkUpService, never()).ensureSession(any());
    }

    @Test
    void authRateLimited_returnsRateLimited() throws Exception {
        doThrow(new LibreRateLimitedException("LibreLinkUp authentication failed: HTTP 429", null))
                .when(libreLinkUpService).ensureSession(userId);

        assertThat(sut.syncUser(userId, false).outcome()).isEqualTo(SyncOutcome.RATE_LIMITED);
    }

    @Test
    void futureNextPollAt_doesNotSkip() throws Exception {
        when(syncStateService.getOrCreate(userId)).thenReturn(UserGlucoseSyncState.builder()
                .nextPollAt(LocalDateTime.now().plusHours(1)).build());
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenReturn(data(1_700_000_000_000L));

        sut.syncUser(userId, false);

        verify(libreLinkUpService).getGlucoseData(any(), anyInt(), eq(userId));
    }

    @Test
    void openCircuit_returnsBreakerRetryAt() throws Exception {
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenThrow(new RuntimeException("503 Service Unavailable"));
        for (int i = 0; i < LibreLinkUpSyncService.UPSTREAM_FAILURE_THRESHOLD; i++) {
            sut.syncUser(userId, false);
        }
        Instant lastFailure = Instant.now();

        SyncResult result = sut.syncUser(userId, false);

        assertThat(result.outcome()).isEqualTo(SyncOutcome.UNAVAILABLE);
        assertThat(result.retryAt()).isNotNull();
        assertThat(Duration.between(lastFailure.plusSeconds(120), result.retryAt()).abs().getSeconds())
                .isLessThanOrEqualTo(5);
    }
}
