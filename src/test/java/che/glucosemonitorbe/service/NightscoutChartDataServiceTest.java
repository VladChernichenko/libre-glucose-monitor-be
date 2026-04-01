package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.NightscoutChartData;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.NightscoutChartDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(repository.findByUserIdAndNightscoutId(eq(testUserId), anyString())).thenReturn(Optional.empty());
        when(repository.save(any(NightscoutChartData.class))).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartData(testUserId, testEntries);

        verify(repository, never()).deleteByUserId(any());
        verify(repository, times(testEntries.size())).save(any(NightscoutChartData.class));
    }

    @Test
    void testStoreChartData_skipsAlreadyStored() {
        when(repository.findByUserIdAndNightscoutId(testUserId, "entry1")).thenReturn(Optional.of(new NightscoutChartData()));
        when(repository.findByUserIdAndNightscoutId(testUserId, "entry2")).thenReturn(Optional.empty());
        when(repository.findByUserIdAndNightscoutId(testUserId, "entry3")).thenReturn(Optional.empty());
        when(repository.save(any(NightscoutChartData.class))).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartData(testUserId, testEntries);

        verify(repository, times(2)).save(any(NightscoutChartData.class));
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
