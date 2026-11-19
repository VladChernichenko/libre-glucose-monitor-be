package che.glucosemonitorbe.service;

import che.glucosemonitorbe.sync.SyncOutcome;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.LibreGlucoseData;
import che.glucosemonitorbe.dto.LibreGlucoseReading;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link LibreLinkUpSyncService#syncUser(UUID, boolean)} writes its readings
 * into the shared CGM cache tagged with {@link CgmReading.DataSource#LIBRE_LINK_UP} -
 * the core invariant of the data-source rename.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class LibreLinkUpSyncServiceCgmTagTest {

    @Mock private UserDataSourceConfigRepository configRepository;
    @Mock private LibreLinkUpService libreLinkUpService;
    @Mock private CgmReadingService cgmReadingService;
    @Mock private UserGlucoseSyncStateService syncStateService;
    @Spy private CircuitBreakerManager circuitBreakerManager = new CircuitBreakerManager();

    @InjectMocks private LibreLinkUpSyncService sut;

    private UUID userId;

    @BeforeEach
    void wireDefaults() {
        userId = UUID.randomUUID();
        // The @Value-bound numeric fields default to 0 under Mockito, which is fine for the
        // forced/no-backoff path used in this test.
        ReflectionTestUtils.setField(sut, "onDemandWaitSeconds", 25L);
        ReflectionTestUtils.setField(sut, "onDemandMinIntervalSeconds", 8L);
    }

    @Test
    @DisplayName("syncUser writes the fetched readings tagged LIBRE_LINK_UP")
    void syncUser_tagsReadingsAsLibreLinkUp() throws Exception {
        when(syncStateService.getOrCreate(userId)).thenReturn(UserGlucoseSyncState.builder().build());
        when(configRepository.findByUserIdAndDataSourceAndIsActiveTrue(
                eq(userId), eq(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)))
                .thenReturn(Optional.of(libreConfig()));
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenReturn(libreData(7.2, 7.5));

        SyncOutcome outcome = sut.syncUser(userId, true).outcome();

        assertThat(outcome).isIn(SyncOutcome.NEW_DATA,
                                 SyncOutcome.NO_CHANGE);

        ArgumentCaptor<List<NightscoutEntryDto>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(cgmReadingService, times(1))
                .storeChartData(eq(userId), entriesCaptor.capture(), eq(CgmReading.DataSource.LIBRE_LINK_UP));

        // Sanity: the Libre readings really did flow through (mmol -> mg/dL conversion + libre id prefix).
        List<NightscoutEntryDto> sent = entriesCaptor.getValue();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).getSgv()).isEqualTo((int) Math.round(7.2 * 18.0));
        assertThat(sent.get(0).getId()).startsWith("llu-");
        assertThat(sent.get(0).getDevice()).isEqualTo("LibreLinkUp");
    }

    @Test
    @DisplayName("syncUser stores only readings after the last recorded timestamp")
    void syncUser_storesOnlyReadingsAfterLastRecorded() throws Exception {
        long recorded = 1_700_000_000_000L;
        when(syncStateService.getOrCreate(userId)).thenReturn(UserGlucoseSyncState.builder()
                .lastSeenEntryTimestamp(recorded)
                .build());
        when(configRepository.findByUserIdAndDataSourceAndIsActiveTrue(
                eq(userId), eq(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)))
                .thenReturn(Optional.of(libreConfig()));
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenReturn(libreDataAt(recorded - 60_000L, recorded, recorded + 60_000L));

        SyncOutcome outcome = sut.syncUser(userId, true).outcome();

        assertThat(outcome).isEqualTo(SyncOutcome.NEW_DATA);
        ArgumentCaptor<List<NightscoutEntryDto>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(cgmReadingService).storeChartData(
                eq(userId), entriesCaptor.capture(), eq(CgmReading.DataSource.LIBRE_LINK_UP));
        assertThat(entriesCaptor.getValue()).extracting(NightscoutEntryDto::getDate)
                .containsExactly(recorded + 60_000L);
    }

    @Test
    @DisplayName("syncUser does not write when every graph point is already recorded")
    void syncUser_skipsStoreWhenNothingIsNewer() throws Exception {
        long recorded = 1_700_000_120_000L;
        when(syncStateService.getOrCreate(userId)).thenReturn(UserGlucoseSyncState.builder()
                .lastSeenEntryTimestamp(recorded)
                .build());
        when(configRepository.findByUserIdAndDataSourceAndIsActiveTrue(
                eq(userId), eq(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)))
                .thenReturn(Optional.of(libreConfig()));
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenReturn(libreDataAt(recorded - 60_000L, recorded));

        SyncOutcome outcome = sut.syncUser(userId, true).outcome();

        assertThat(outcome).isEqualTo(SyncOutcome.NO_CHANGE);
        verify(cgmReadingService, never()).storeChartData(any(), any(), any());
    }

    @Test
    @DisplayName("syncUser never calls storeChartData with NIGHTSCOUT")
    void syncUser_neverTagsAsNightscout() throws Exception {
        when(syncStateService.getOrCreate(userId)).thenReturn(UserGlucoseSyncState.builder().build());
        when(configRepository.findByUserIdAndDataSourceAndIsActiveTrue(
                eq(userId), eq(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)))
                .thenReturn(Optional.of(libreConfig()));
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenReturn(libreData(6.0));

        sut.syncUser(userId, true);

        verify(cgmReadingService, org.mockito.Mockito.never())
                .storeChartData(eq(userId), anyList(), eq(CgmReading.DataSource.NIGHTSCOUT));
    }

    // -- fixtures -----------------------------------------------------------------

    private UserDataSourceConfig libreConfig() {
        // UserDataSourceConfig stores its owner as a @ManyToOne User association,
        // but the doSync flow only reads the libre_* fields - no need to wire a User here.
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
        long base = System.currentTimeMillis();
        long[] times = new long[mmolValues.length];
        for (int i = 0; i < mmolValues.length; i++) {
            times[i] = base + i * 60_000L;
        }
        return libreDataAt(mmolValues, times);
    }

    private LibreGlucoseData libreDataAt(long... epochMs) {
        double[] values = new double[epochMs.length];
        java.util.Arrays.fill(values, 6.0);
        return libreDataAt(values, epochMs);
    }

    private LibreGlucoseData libreDataAt(double[] mmolValues, long[] epochMs) {
        LibreGlucoseData out = new LibreGlucoseData();
        out.setPatientId("patient-1");
        java.util.List<LibreGlucoseReading> readings = new java.util.ArrayList<>();
        for (int i = 0; i < epochMs.length; i++) {
            LibreGlucoseReading r = new LibreGlucoseReading();
            r.setTimestamp(new Date(epochMs[i]));
            r.setValue(mmolValues[i]);
            r.setTrend(0);
            readings.add(r);
        }
        out.setData(readings);
        return out;
    }
}
