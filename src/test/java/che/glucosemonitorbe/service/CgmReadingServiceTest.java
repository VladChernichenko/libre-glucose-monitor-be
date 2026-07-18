package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CgmReadingServiceTest {

    @Mock
    private CgmReadingRepository repository;

    @InjectMocks
    private CgmReadingService chartDataService;

    private UUID testUserId;
    private List<NightscoutEntryDto> testEntries;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testEntries = Arrays.asList(
            new NightscoutEntryDto("entry1", 120, 1640995200000L, "2022-01-01T00:00:00.000Z", 1, "Flat",        "device1", "sgv", 0, "2022-01-01T00:00:00.000Z"),
            new NightscoutEntryDto("entry2", 130, 1640998800000L, "2022-01-01T01:00:00.000Z", 2, "FortyFiveUp", "device1", "sgv", 0, "2022-01-01T01:00:00.000Z"),
            new NightscoutEntryDto("entry3", 140, 1641002400000L, "2022-01-01T02:00:00.000Z", 3, "SingleUp",    "device1", "sgv", 0, "2022-01-01T02:00:00.000Z")
        );
    }

    // -- storeChartData / NIGHTSCOUT -----------------------------------------------

    @Test
    @DisplayName("storeChartData inserts every new entry and tags them NIGHTSCOUT")
    void storeChartData_nightscout_insertsAndTagsSource() {
        when(repository.findExistingExternalIds(eq(testUserId), eq(CgmReading.DataSource.NIGHTSCOUT), anyList()))
                .thenReturn(Collections.emptyList());
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartData(testUserId, testEntries, CgmReading.DataSource.NIGHTSCOUT);

        verify(repository, never()).deleteByUserId(any());
        ArgumentCaptor<List<CgmReading>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAll(captor.capture());
        assertEquals(testEntries.size(), captor.getValue().size());
        assertTrue(captor.getValue().stream()
                .allMatch(r -> r.getDataSource() == CgmReading.DataSource.NIGHTSCOUT));
    }

    @Test
    @DisplayName("storeChartData skips entries already stored under the same source")
    void storeChartData_skipsAlreadyStored() {
        when(repository.findExistingExternalIds(eq(testUserId), eq(CgmReading.DataSource.NIGHTSCOUT), anyList()))
                .thenReturn(List.of("entry1"));
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartData(testUserId, testEntries, CgmReading.DataSource.NIGHTSCOUT);

        ArgumentCaptor<List<CgmReading>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAll(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    // -- storeChartData / LIBRE_LINK_UP --------------------------------------------

    @Test
    @DisplayName("storeChartData with LIBRE_LINK_UP tags rows correctly and scopes the existence check")
    void storeChartData_libre_tagsAndScopes() {
        when(repository.findExistingExternalIds(eq(testUserId), eq(CgmReading.DataSource.LIBRE_LINK_UP), anyList()))
                .thenReturn(Collections.emptyList());
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartData(testUserId, testEntries, CgmReading.DataSource.LIBRE_LINK_UP);

        verify(repository).findExistingExternalIds(
                eq(testUserId), eq(CgmReading.DataSource.LIBRE_LINK_UP), anyList());
        verify(repository, never()).findExistingExternalIds(
                eq(testUserId), eq(CgmReading.DataSource.NIGHTSCOUT), anyList());

        ArgumentCaptor<List<CgmReading>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertTrue(captor.getValue().stream()
                .allMatch(r -> r.getDataSource() == CgmReading.DataSource.LIBRE_LINK_UP));
    }

    @Test
    @DisplayName("storeChartData routes entries without external id through the timestamp existence check")
    void storeChartData_noExternalId_usesTimestampLookup() {
        List<NightscoutEntryDto> tsOnly = List.of(
                new NightscoutEntryDto(null, 105, 1L, null, 0, "Flat", null, "sgv", 0, null),
                new NightscoutEntryDto(null, 110, 2L, null, 0, "Flat", null, "sgv", 0, null)
        );
        when(repository.findExistingDateTimestamps(eq(testUserId), eq(CgmReading.DataSource.LIBRE_LINK_UP), anyList()))
                .thenReturn(List.of(2L));
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartData(testUserId, tsOnly, CgmReading.DataSource.LIBRE_LINK_UP);

        verify(repository, never()).findExistingExternalIds(any(), any(), anyList());
        ArgumentCaptor<List<CgmReading>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size()); // ts=2 already stored, only ts=1 inserted
        assertEquals(1L, captor.getValue().get(0).getDateTimestamp());
    }

    @Test
    @DisplayName("storeChartData dedupes within a single batch before hitting the DB")
    void storeChartData_dedupesWithinBatch() {
        List<NightscoutEntryDto> withDupes = List.of(
                new NightscoutEntryDto("a", 100, 1L, null, 0, "Flat", null, "sgv", 0, null),
                new NightscoutEntryDto("a", 105, 2L, null, 0, "Flat", null, "sgv", 0, null), // dup id
                new NightscoutEntryDto("b", 110, 3L, null, 0, "Flat", null, "sgv", 0, null)
        );
        when(repository.findExistingExternalIds(eq(testUserId), eq(CgmReading.DataSource.NIGHTSCOUT), anyList()))
                .thenReturn(Collections.emptyList());
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartData(testUserId, withDupes, CgmReading.DataSource.NIGHTSCOUT);

        ArgumentCaptor<List<CgmReading>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    @DisplayName("storeChartData with empty/null input is a no-op")
    void storeChartData_emptyInput_noop() {
        chartDataService.storeChartData(testUserId, List.of(), CgmReading.DataSource.NIGHTSCOUT);
        chartDataService.storeChartData(testUserId, null, CgmReading.DataSource.LIBRE_LINK_UP);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("storeChartDataAsync delegates to storeChartData with the same source")
    void storeChartDataAsync_delegates() {
        when(repository.findExistingExternalIds(eq(testUserId), eq(CgmReading.DataSource.LIBRE_LINK_UP), anyList()))
                .thenReturn(Collections.emptyList());
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chartDataService.storeChartDataAsync(testUserId, testEntries, CgmReading.DataSource.LIBRE_LINK_UP);

        ArgumentCaptor<List<CgmReading>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertTrue(captor.getValue().stream()
                .allMatch(r -> r.getDataSource() == CgmReading.DataSource.LIBRE_LINK_UP));
    }

    @Test
    @DisplayName("storeChartDataAsync swallows exceptions instead of propagating")
    void storeChartDataAsync_swallowsExceptions() {
        when(repository.findExistingExternalIds(any(), any(), anyList()))
                .thenThrow(new RuntimeException("db down"));

        // Should not throw - async writer absorbs the failure and logs it.
        chartDataService.storeChartDataAsync(testUserId, testEntries, CgmReading.DataSource.NIGHTSCOUT);
    }

    // -- read paths ---------------------------------------------------------------

    @Test
    @DisplayName("getChartData returns rows ordered by timestamp")
    void getChartData_delegates() {
        List<CgmReading> mockData = Arrays.asList(
            CgmReading.builder()
                .userId(testUserId)
                .dataSource(CgmReading.DataSource.NIGHTSCOUT)
                .externalId("entry1")
                .sgv(120)
                .build()
        );
        when(repository.findByUserIdOrderByDateTimestampAsc(testUserId)).thenReturn(mockData);

        List<CgmReading> result = chartDataService.getChartData(testUserId);

        assertEquals(1, result.size());
        assertEquals("entry1", result.get(0).getExternalId());
        assertEquals(120, result.get(0).getSgv());
    }

    @Test
    @DisplayName("getChartDataAsEntries maps externalId back into NightscoutEntryDto.id")
    void getChartDataAsEntries_mapsExternalIdToDtoId() {
        List<CgmReading> mockData = Arrays.asList(
            CgmReading.builder()
                .userId(testUserId)
                .dataSource(CgmReading.DataSource.LIBRE_LINK_UP)
                .externalId("libre-abc")
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
        assertEquals("libre-abc", result.get(0).getId());
        assertEquals(120, result.get(0).getSgv());
        assertEquals("Flat", result.get(0).getDirection());
    }

    @Test
    void clearChartData_delegates() {
        chartDataService.clearChartData(testUserId);
        verify(repository, times(1)).deleteByUserId(testUserId);
    }

    // -- getChartDataAsEntriesSince ------------------------------------------------

    @Test
    @DisplayName("getChartDataAsEntriesSince delegates to the correct repository method with the given timestamp")
    void getChartDataAsEntriesSince_delegatesToCorrectRepoMethod() {
        long sinceEpochMs = 1640998800000L;
        List<CgmReading> readings = List.of(
                CgmReading.builder()
                        .userId(testUserId)
                        .dataSource(CgmReading.DataSource.LIBRE_LINK_UP)
                        .externalId("llu-abc")
                        .sgv(115)
                        .dateTimestamp(1641002400000L)
                        .direction("Flat")
                        .build()
        );
        when(repository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(testUserId, sinceEpochMs))
                .thenReturn(readings);

        List<NightscoutEntryDto> result = chartDataService.getChartDataAsEntriesSince(testUserId, sinceEpochMs);

        assertEquals(1, result.size());
        assertEquals("llu-abc", result.get(0).getId());
        assertEquals(115, result.get(0).getSgv());
        verify(repository).findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(testUserId, sinceEpochMs);
        verify(repository, never()).findByUserIdOrderByDateTimestampAsc(testUserId);
    }

    @Test
    @DisplayName("getChartDataAsEntriesSince returns empty list when no newer entries exist")
    void getChartDataAsEntriesSince_returnsEmptyWhenNoneNewer() {
        when(repository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(testUserId, 9_999_999_999L))
                .thenReturn(Collections.emptyList());

        List<NightscoutEntryDto> result = chartDataService.getChartDataAsEntriesSince(testUserId, 9_999_999_999L);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getChartDataAsEntriesSince maps all DTO fields correctly (same as getChartDataAsEntries)")
    void getChartDataAsEntriesSince_mapsAllFields() {
        long since = 1000L;
        CgmReading reading = CgmReading.builder()
                .userId(testUserId)
                .dataSource(CgmReading.DataSource.NIGHTSCOUT)
                .externalId("ns-xyz")
                .sgv(142)
                .dateTimestamp(2000L)
                .dateString("2022-01-01T02:00:00.000Z")
                .trend(3)
                .direction("SingleUp")
                .device("dexcom")
                .type("sgv")
                .utcOffset(60)
                .sysTime("2022-01-01T02:00:00.000Z")
                .build();
        when(repository.findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(testUserId, since))
                .thenReturn(List.of(reading));

        NightscoutEntryDto dto = chartDataService.getChartDataAsEntriesSince(testUserId, since).get(0);

        assertEquals("ns-xyz",   dto.getId());
        assertEquals(142,        dto.getSgv());
        assertEquals(2000L,      dto.getDate());
        assertEquals("SingleUp", dto.getDirection());
        assertEquals("dexcom",   dto.getDevice());
        assertEquals(60,         dto.getUtcOffset());
    }
}
