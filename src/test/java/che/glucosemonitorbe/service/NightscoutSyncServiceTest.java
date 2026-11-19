package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.sync.SyncOutcome;
import che.glucosemonitorbe.sync.SyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class NightscoutSyncServiceTest {

    private static final int ENTRY_COUNT = 100;

    private NightScoutIntegration integration;
    private CgmReadingService cgmReadingService;
    private UserGlucoseSyncStateService stateService;
    private NightscoutSyncService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        integration = Mockito.mock(NightScoutIntegration.class);
        cgmReadingService = Mockito.mock(CgmReadingService.class);
        stateService = Mockito.mock(UserGlucoseSyncStateService.class);
        service = new NightscoutSyncService(integration, cgmReadingService, stateService, ENTRY_COUNT);
    }

    private void stateWithLastSeen(Long lastSeen) {
        when(stateService.getOrCreate(userId)).thenReturn(
                UserGlucoseSyncState.builder().userId(userId).lastSeenEntryTimestamp(lastSeen).build());
    }

    private static NightscoutEntryDto entry(Long date) {
        NightscoutEntryDto e = new NightscoutEntryDto();
        e.setDate(date);
        return e;
    }

    private void verifyNoMarks() {
        verify(stateService).getOrCreate(userId);
        verifyNoMoreInteractions(stateService);
    }

    @Test
    void newerEntry_returnsNewData() {
        stateWithLastSeen(1000L);
        List<NightscoutEntryDto> entries = List.of(entry(1500L), entry(2000L), entry(null));
        when(integration.getGlucoseEntries(userId, ENTRY_COUNT)).thenReturn(entries);

        SyncResult result = service.syncUser(userId);

        assertEquals(SyncOutcome.NEW_DATA, result.outcome());
        assertEquals(2000L, result.newestTimestamp());
        verify(cgmReadingService).storeChartData(userId, entries, CgmReading.DataSource.NIGHTSCOUT);
        verifyNoMarks();
    }

    @Test
    void sameNewest_returnsNoChange() {
        stateWithLastSeen(2000L);
        List<NightscoutEntryDto> entries = List.of(entry(2000L));
        when(integration.getGlucoseEntries(userId, ENTRY_COUNT)).thenReturn(entries);

        SyncResult result = service.syncUser(userId);

        assertEquals(SyncOutcome.NO_CHANGE, result.outcome());
        verify(cgmReadingService).storeChartData(userId, entries, CgmReading.DataSource.NIGHTSCOUT);
        verifyNoMarks();
    }

    @Test
    void emptyEntries_returnsNoChange() {
        stateWithLastSeen(null);
        when(integration.getGlucoseEntries(userId, ENTRY_COUNT)).thenReturn(List.of());

        SyncResult result = service.syncUser(userId);

        assertEquals(SyncOutcome.NO_CHANGE, result.outcome());
        verifyNoMarks();
    }

    @Test
    void integrationThrows_returnsError() {
        stateWithLastSeen(1000L);
        when(integration.getGlucoseEntries(eq(userId), anyInt())).thenThrow(new RuntimeException("429 Too Many Requests"));

        SyncResult result = service.syncUser(userId);

        assertEquals(SyncOutcome.ERROR, result.outcome());
        verifyNoMarks();
        Mockito.verifyNoInteractions(cgmReadingService);
    }
}
