package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.NightscoutChartData;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.NightscoutChartDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
    @Transactional
    public void storeChartData(UUID userId, List<NightscoutEntryDto> entries) {
        log.info("Storing {} chart data entries for user {}", entries.size(), userId);
        
        // Clear existing data for this user
        repository.deleteByUserId(userId);
        log.debug("Cleared existing chart data for user {}", userId);
        
        // Store new data, limiting to 100 entries
        List<NightscoutEntryDto> limitedEntries = entries.stream()
                .limit(MAX_ROWS_PER_USER)
                .collect(Collectors.toList());
        
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
     * Store chart data with append-only strategy
     * Preserves existing data and only adds new entries that don't already exist
     */
    @Transactional
    public void storeChartDataSmart(UUID userId, List<NightscoutEntryDto> entries) {
        log.info("Storing {} chart data entries for user {} with append-only strategy", entries.size(), userId);
        
        if (entries.isEmpty()) {
            log.debug("No entries to store for user {}", userId);
            return;
        }
        
        // Get existing data to check for duplicates
        List<NightscoutChartData> existingData = repository.findByUserIdOrderByRowIndex(userId);
        Set<String> existingNightscoutIds = existingData.stream()
                .map(NightscoutChartData::getNightscoutId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toSet());
        
        // Filter out entries that already exist (by Nightscout ID)
        List<NightscoutEntryDto> newEntries = entries.stream()
                .filter(entry -> entry.getId() == null || !existingNightscoutIds.contains(entry.getId()))
                .collect(Collectors.toList());
        
        if (newEntries.isEmpty()) {
            log.info("All {} entries already exist for user {}, no new data to store", entries.size(), userId);
            return;
        }
        
        // Check if we have space for new entries
        int currentCount = existingData.size();
        int availableSpace = MAX_ROWS_PER_USER - currentCount;
        
        if (availableSpace <= 0) {
            log.warn("No space available for new entries (current: {}, max: {}), user {}", 
                    currentCount, MAX_ROWS_PER_USER, userId);
            return;
        }
        
        // Limit new entries to available space
        List<NightscoutEntryDto> entriesToStore = newEntries.stream()
                .limit(availableSpace)
                .collect(Collectors.toList());
        
        log.info("Storing {} new entries for user {} (filtered from {} total entries, {} already existed)", 
                entriesToStore.size(), userId, entries.size(), entries.size() - newEntries.size());
        
        // Store new entries starting from the next available row index
        int startIndex = currentCount;
        for (int i = 0; i < entriesToStore.size(); i++) {
            NightscoutEntryDto entry = entriesToStore.get(i);
            NightscoutChartData chartData = NightscoutChartData.builder()
                    .userId(userId)
                    .rowIndex(startIndex + i)
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
        
        log.info("Successfully stored {} new chart data entries for user {} (total entries now: {})", 
                entriesToStore.size(), userId, currentCount + entriesToStore.size());
    }
    
    /**
     * Store chart data with rolling window approach
     * When at capacity, removes oldest entries to make room for new ones
     */
    @Transactional
    public void storeChartDataWithRollingWindow(UUID userId, List<NightscoutEntryDto> entries) {
        log.info("Storing {} chart data entries for user {} with rolling window approach", entries.size(), userId);
        
        if (entries.isEmpty()) {
            log.debug("No entries to store for user {}", userId);
            return;
        }
        
        // Get existing data to check for duplicates
        List<NightscoutChartData> existingData = repository.findByUserIdOrderByRowIndex(userId);
        Set<String> existingNightscoutIds = existingData.stream()
                .map(NightscoutChartData::getNightscoutId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toSet());
        
        // Filter out entries that already exist (by Nightscout ID)
        List<NightscoutEntryDto> newEntries = entries.stream()
                .filter(entry -> entry.getId() == null || !existingNightscoutIds.contains(entry.getId()))
                .collect(Collectors.toList());
        
        if (newEntries.isEmpty()) {
            log.info("All {} entries already exist for user {}, no new data to store", entries.size(), userId);
            return;
        }
        
        int currentCount = existingData.size();
        int entriesToAdd = newEntries.size();
        
        // If we need to remove old entries to make room
        if (currentCount + entriesToAdd > MAX_ROWS_PER_USER) {
            int entriesToRemove = (currentCount + entriesToAdd) - MAX_ROWS_PER_USER;
            log.info("Removing {} oldest entries to make room for new data", entriesToRemove);
            
            // Remove oldest entries (they have the smallest row indexes)
            for (int i = 0; i < entriesToRemove; i++) {
                repository.deleteByUserIdAndRowIndex(userId, i);
            }
            
            // Shift remaining entries down
            List<NightscoutChartData> remainingData = repository.findByUserIdOrderByRowIndex(userId);
            for (NightscoutChartData data : remainingData) {
                if (data.getRowIndex() >= entriesToRemove) {
                    data.setRowIndex(data.getRowIndex() - entriesToRemove);
                    repository.save(data);
                }
            }
            
            currentCount = currentCount - entriesToRemove;
        }
        
        log.info("Storing {} new entries for user {} (filtered from {} total entries, {} already existed)", 
                newEntries.size(), userId, entries.size(), entries.size() - newEntries.size());
        
        // Store new entries starting from the next available row index
        int startIndex = currentCount;
        for (int i = 0; i < newEntries.size(); i++) {
            NightscoutEntryDto entry = newEntries.get(i);
            NightscoutChartData chartData = NightscoutChartData.builder()
                    .userId(userId)
                    .rowIndex(startIndex + i)
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
        
        log.info("Successfully stored {} new chart data entries for user {} with rolling window (total entries: {})", 
                newEntries.size(), userId, currentCount + newEntries.size());
    }
    
    /**
     * Store or update chart data for a user using the rolling window approach
     * This method maintains exactly 100 rows by replacing the oldest data
     */
    @Transactional
    public void storeChartDataRolling(UUID userId, List<NightscoutEntryDto> entries) {
        log.info("Storing {} chart data entries for user {} using rolling window", entries.size(), userId);
        
        // Limit entries to 100
        List<NightscoutEntryDto> limitedEntries = entries.stream()
                .limit(MAX_ROWS_PER_USER)
                .collect(Collectors.toList());
        
        // Get current row count
        long currentCount = repository.countByUserId(userId);
        
        if (currentCount == 0) {
            // No existing data, store all entries
            storeChartData(userId, limitedEntries);
            return;
        }
        
        // Store new entries, replacing oldest data if needed
        for (int i = 0; i < limitedEntries.size(); i++) {
            NightscoutEntryDto entry = limitedEntries.get(i);
            
            // Find next available row index
            Integer rowIndex = findNextAvailableRowIndex(userId, i);
            
            NightscoutChartData chartData = NightscoutChartData.builder()
                    .userId(userId)
                    .rowIndex(rowIndex)
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
        
        log.info("Successfully stored {} chart data entries for user {} using rolling window", 
                limitedEntries.size(), userId);
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

