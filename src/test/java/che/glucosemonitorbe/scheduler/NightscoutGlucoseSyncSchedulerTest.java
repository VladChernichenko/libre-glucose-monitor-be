package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.NightscoutChartDataService;
import che.glucosemonitorbe.service.UserGlucoseSyncStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class NightscoutGlucoseSyncSchedulerTest {

    @Mock
    private UserDataSourceConfigRepository configRepository;
    @Mock
    private NightScoutIntegration nightScoutIntegration;
    @Mock
    private NightscoutChartDataService nightscoutChartDataService;
    @Mock
    private UserGlucoseSyncStateService syncStateService;

    @InjectMocks
    private NightscoutGlucoseSyncScheduler scheduler;

    @BeforeEach
    void setEntryCount() {
        ReflectionTestUtils.setField(scheduler, "entryCount", 100);
    }

    @Test
    void syncNightscoutForAllUsers_doesNothingWhenNoUsers() {
        when(configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.NIGHTSCOUT))
                .thenReturn(Collections.emptyList());

        scheduler.syncNightscoutForAllUsers();

        verifyNoInteractions(nightScoutIntegration);
        verifyNoInteractions(nightscoutChartDataService);
    }

    @Test
    void syncNightscoutForAllUsers_fetchesAndStoresPerUser() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        when(configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.NIGHTSCOUT))
                .thenReturn(List.of(u1, u2));
        List<NightscoutEntryDto> batch = List.of(new NightscoutEntryDto("a", 100, 1L, null, 0, "Flat", null, "sgv", 0, null));
        when(nightScoutIntegration.getGlucoseEntries(any(), eq(100))).thenReturn(batch);
        when(syncStateService.getOrCreate(any())).thenReturn(UserGlucoseSyncState.builder().build());

        scheduler.syncNightscoutForAllUsers();

        verify(nightScoutIntegration).getGlucoseEntries(u1, 100);
        verify(nightScoutIntegration).getGlucoseEntries(u2, 100);
        verify(nightscoutChartDataService).storeChartData(u1, batch);
        verify(nightscoutChartDataService).storeChartData(u2, batch);
    }

    @Test
    void syncNightscoutForAllUsers_continuesAfterOneUserFails() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        when(configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.NIGHTSCOUT))
                .thenReturn(List.of(u1, u2));
        when(syncStateService.getOrCreate(any())).thenReturn(UserGlucoseSyncState.builder().build());
        when(nightScoutIntegration.getGlucoseEntries(u1, 100)).thenThrow(new RuntimeException("down"));
        when(nightScoutIntegration.getGlucoseEntries(u2, 100)).thenReturn(List.of());

        scheduler.syncNightscoutForAllUsers();

        verify(nightscoutChartDataService).storeChartData(eq(u2), any());
        verify(nightscoutChartDataService, never()).storeChartData(eq(u1), any());
    }
}
