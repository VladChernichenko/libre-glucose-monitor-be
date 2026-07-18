package che.glucosemonitorbe.service;

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
import org.mockito.junit.jupiter.MockitoExtension;
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

    @InjectMocks private LibreLinkUpSyncService sut;

    private UUID userId;

    @BeforeEach
    void wireDefaults() {
        userId = UUID.randomUUID();
        // The @Value-bound numeric fields default to 0 under Mockito, which is fine for the
        // forced/no-backoff path used in this test.
        ReflectionTestUtils.setField(sut, "fastIntervalMinutes", 5L);
        ReflectionTestUtils.setField(sut, "slowIntervalMinutes", 15L);
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
        when(libreLinkUpService.isAuthenticated(userId)).thenReturn(true);
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), eq(userId)))
                .thenReturn(libreData(7.2, 7.5));

        LibreLinkUpSyncService.Outcome outcome = sut.syncUser(userId, true);

        assertThat(outcome).isIn(LibreLinkUpSyncService.Outcome.NEW_DATA,
                                 LibreLinkUpSyncService.Outcome.NO_CHANGE);

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
    @DisplayName("syncUser never calls storeChartData with NIGHTSCOUT")
    void syncUser_neverTagsAsNightscout() throws Exception {
        when(syncStateService.getOrCreate(userId)).thenReturn(UserGlucoseSyncState.builder().build());
        when(configRepository.findByUserIdAndDataSourceAndIsActiveTrue(
                eq(userId), eq(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)))
                .thenReturn(Optional.of(libreConfig()));
        when(libreLinkUpService.isAuthenticated(userId)).thenReturn(true);
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
        LibreGlucoseData out = new LibreGlucoseData();
        out.setPatientId("patient-1");
        java.util.List<LibreGlucoseReading> readings = new java.util.ArrayList<>();
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
