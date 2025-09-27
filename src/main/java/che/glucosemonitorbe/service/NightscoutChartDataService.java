package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.NightscoutChartData;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.NightscoutChartDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NightscoutChartDataService {
    
    private final NightscoutChartDataRepository repository;
    private static final int MAX_ROWS_PER_USER = 100;
    
    /**
     * Store or update chart data for a user
     * Maintains exactly 100 rows per user, replacing oldest data when full
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void storeChartData(UUID userId, List<NightscoutEntryDto> entries) {
        log.info("Storing {} chart data entries for user {}", entries.size(), userId);
        

        // Store new data, limiting to 100 entries
        List<NightscoutEntryDto> limitedEntries = entries.stream()
                .limit(MAX_ROWS_PER_USER)
                .toList();
        repository.deleteByUserId(userId);
        for (int i = 0; i < limitedEntries.size(); i++) {
            NightscoutEntryDto entry = limitedEntries.get(i);
            NightscoutChartData chartData = NightscoutChartData.builder()
                    .userId(userId)
                    .rowIndex(i)
                    .nightscoutId(entry.getId())
                    .sgv(entry.getSgv())
                    .dateTimestamp(entry.getDate())
                    .dateString(entry.getDateString())
                    .trend(entry.getTrend())
                    .direction(entry.getDirection())
                    .device(entry.getDevice())
                    .type(entry.getType())
                    .utcOffset(entry.getUtcOffset())
                    .sysTime(entry.getSysTime())
                    .lastUpdated(LocalDateTime.now())
                    .build();
            repository.save(chartData);
        }
        
        log.info("Successfully stored {} chart data entries for user {}", limitedEntries.size(), userId);
    }
    
    /**
     * Get all chart data for a user
     */
    public List<NightscoutChartData> getChartData(UUID userId) {
        log.debug("Retrieving chart data for user {}", userId);
        return repository.findByUserIdOrderByRowIndex(userId);
    }
    
    /**
     * Convert stored chart data back to NightscoutEntryDto format
     */
    public List<NightscoutEntryDto> getChartDataAsEntries(UUID userId) {
        List<NightscoutChartData> chartData = getChartData(userId);
        return chartData.stream()
                .map(this::convertToEntryDto)
                .collect(Collectors.toList());
    }
    
    /**
     * Clear all chart data for a user
     */
    @Transactional
    public void clearChartData(UUID userId) {
        log.info("Clearing all chart data for user {}", userId);
        repository.deleteByUserId(userId);
    }
    
    /**
     * Clean up old chart data (for maintenance)
     */
    @Transactional
    public int cleanupOldData(LocalDateTime cutoffDate) {
        log.info("Cleaning up chart data older than {}", cutoffDate);
        return repository.deleteOldChartData(cutoffDate);
    }
    
    /**
     * Find next available row index for a user
     */
    private Integer findNextAvailableRowIndex(UUID userId, int preferredIndex) {
        // First try the preferred index
        if (repository.findByUserIdAndRowIndex(userId, preferredIndex) == null) {
            return preferredIndex;
        }
        
        // Find next available index
        Integer nextAvailable = repository.findNextAvailableRowIndex(userId);
        if (nextAvailable != null && nextAvailable != -1) {
            return nextAvailable;
        }
        
        // All 100 rows are occupied, replace the oldest
        Integer oldestIndex = repository.findOldestRowIndex(userId);
        return oldestIndex != null ? oldestIndex : preferredIndex;
    }
    
    /**
     * Convert stored chart data to NightscoutEntryDto
     */
    private NightscoutEntryDto convertToEntryDto(NightscoutChartData chartData) {
        return new NightscoutEntryDto(
                chartData.getNightscoutId(),
                chartData.getSgv(),
                chartData.getDateTimestamp(),
                chartData.getDateString(),
                chartData.getTrend(),
                chartData.getDirection(),
                chartData.getDevice(),
                chartData.getType(),
                chartData.getUtcOffset(),
                chartData.getSysTime()
        );
    }
}

