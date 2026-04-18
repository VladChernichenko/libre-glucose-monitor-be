package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.NightscoutChartData;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.NightscoutChartDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NightscoutChartDataServiceTest {

    @Mock
    private NightscoutChartDataRepository repository;

    @InjectMocks
    private NightscoutChartDataService chartDataService;

    private UUID testUserId;
    private List<NightscoutEntryDto> testEntries;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testEntries = Arrays.asList(
            new NightscoutEntryDto("entry1", 120, 1640995200000L, "2022-01-01T00:00:00.000Z", 1, "Flat", "device1", "sgv", 0, "2022-01-01T00:00:00.000Z"),
            new NightscoutEntryDto("entry2", 130, 1640998800000L, "2022-01-01T01:00:00.000Z", 2, "FortyFiveUp", "device1", "sgv", 0, "2022-01-01T01:00:00.000Z"),
            new NightscoutEntryDto("entry3", 140, 1641002400000L, "2022-01-01T02:00:00.000Z", 3, "SingleUp", "device1", "sgv", 0, "2022-01-01T02:00:00.000Z")
        );
    }

    @Test
    void testStoreChartData_insertsOnlyNew() {
        when(repository.findExistingNightscoutIds(eq(testUserId), anyList())).thenReturn(Collections.emptyList());
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartData(testUserId, testEntries);

        verify(repository, never()).deleteByUserId(any());
        ArgumentCaptor<List<NightscoutChartData>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAll(captor.capture());
        assertEquals(testEntries.size(), captor.getValue().size());
    }

    @Test
    void testStoreChartData_skipsAlreadyStored() {
        when(repository.findExistingNightscoutIds(eq(testUserId), anyList()))
                .thenReturn(List.of("entry1"));
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartData(testUserId, testEntries);

        ArgumentCaptor<List<NightscoutChartData>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAll(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void testGetChartData() {
        List<NightscoutChartData> mockData = Arrays.asList(
            NightscoutChartData.builder()
                .userId(testUserId)
                .nightscoutId("entry1")
                .sgv(120)
                .build()
        );
        when(repository.findByUserIdOrderByDateTimestampAsc(testUserId)).thenReturn(mockData);

        List<NightscoutChartData> result = chartDataService.getChartData(testUserId);

        assertEquals(1, result.size());
        assertEquals("entry1", result.get(0).getNightscoutId());
        assertEquals(120, result.get(0).getSgv());
    }

    @Test
    void testGetChartDataAsEntries() {
        List<NightscoutChartData> mockData = Arrays.asList(
            NightscoutChartData.builder()
                .userId(testUserId)
                .nightscoutId("entry1")
                .sgv(120)
                .dateTimestamp(1640995200000L)
                .dateString("2022-01-01T00:00:00.000Z")
                .trend(1)
                .direction("Flat")
                .device("device1")
                .type("sgv")
                .utcOffset(0)
                .sysTime("2022-01-01T00:00:00.000Z")
                .build()
        );
        when(repository.findByUserIdOrderByDateTimestampAsc(testUserId)).thenReturn(mockData);

        List<NightscoutEntryDto> result = chartDataService.getChartDataAsEntries(testUserId);

        assertEquals(1, result.size());
        assertEquals("entry1", result.get(0).getId());
        assertEquals(120, result.get(0).getSgv());
        assertEquals("Flat", result.get(0).getDirection());
    }

    @Test
    void testClearChartData() {
        chartDataService.clearChartData(testUserId);
        verify(repository, times(1)).deleteByUserId(testUserId);
    }
}
