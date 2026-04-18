package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.service.NightscoutChartDataService;
import che.glucosemonitorbe.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/nightscout")
@RequiredArgsConstructor
public class NightscoutController {
    
    private final NightScoutIntegration nightScoutIntegration;
    private final NightscoutChartDataService chartDataService;
    private final UserService userService;
    
    @GetMapping("/entries")
    public ResponseEntity<List<NightscoutEntryDto>> getGlucoseEntries(
            @RequestParam(value = "count", defaultValue = "100") int count,
            @RequestParam(value = "useStored", defaultValue = "false") boolean useStored,
            @RequestHeader(value = "X-Timezone-Offset", required = false) String timezoneOffset,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            Authentication authentication) {
        
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();

        if (useStored) {
            List<NightscoutEntryDto> storedEntries = chartDataService.getChartDataAsEntries(userId);
            if (storedEntries.size() > count) {
                int from = Math.max(0, storedEntries.size() - count);
                storedEntries = storedEntries.subList(from, storedEntries.size());
            }
            applyTimezoneOffsetToEntries(storedEntries, timezoneOffset);
            log.info("Returning {} stored chart entries for user {}", storedEntries.size(), authentication.getName());
            return ResponseEntity.ok(storedEntries);
        }

        try {
            // The cache in NightScoutIntegration returns a shared list — copy before mutating so
            // per-request timezone offsets don't corrupt the cached value for the next caller.
            List<NightscoutEntryDto> cached = nightScoutIntegration.getGlucoseEntries(userId, count);
            List<NightscoutEntryDto> entries = copyEntries(cached);
            log.info("Fetched {} entries from Nightscout", entries.size());

            applyTimezoneOffsetToEntries(entries, timezoneOffset);

            // Offload persistence to a dedicated pool so the HTTP response returns immediately.
            chartDataService.storeChartDataAsync(userId, entries);
            log.debug("Queued {} entries for async persistence for user {}", entries.size(), authentication.getName());
            return ResponseEntity.ok(entries);
        } catch (Exception e) {
            log.error("Failed to fetch glucose entries", e);
            throw new RuntimeException("Failed to fetch glucose data: " + rootCauseMessage(e), e);
        }
    }

    private static List<NightscoutEntryDto> copyEntries(List<NightscoutEntryDto> source) {
        List<NightscoutEntryDto> out = new ArrayList<>(source.size());
        for (NightscoutEntryDto e : source) {
            out.add(new NightscoutEntryDto(
                    e.getId(), e.getSgv(), e.getDate(), e.getDateString(),
                    e.getTrend(), e.getDirection(), e.getDevice(), e.getType(),
                    e.getUtcOffset(), e.getSysTime()));
        }
        return out;
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable c = e;
        String best = e.getMessage() != null ? e.getMessage() : "";
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
            if (c.getMessage() != null && !c.getMessage().isBlank()) {
                best = c.getMessage();
            }
        }
        return best.isBlank() ? "Unknown error" : best;
    }

    private void applyTimezoneOffsetToEntries(List<NightscoutEntryDto> entries, String timezoneOffset) {
        if (timezoneOffset == null || entries.isEmpty()) {
            return;
        }
        try {
            int offsetMinutes = Integer.parseInt(timezoneOffset);
            int utcOffset = -offsetMinutes;
            log.info("Applying frontend timezone offset {} minutes (UTC offset: {}) to {} entries",
                    offsetMinutes, utcOffset, entries.size());

            for (NightscoutEntryDto entry : entries) {
                Integer existing = entry.getUtcOffset();
                if (existing == null || existing == 0) {
                    entry.setUtcOffset(utcOffset);
                    log.debug("Set utcOffset to {} for entry {}", utcOffset, entry.getId());
                }
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid timezone offset format: {}", timezoneOffset);
        }
    }
    
    @GetMapping("/entries/current")
    public ResponseEntity<NightscoutEntryDto> getCurrentGlucose(
            @RequestHeader(value = "X-Timezone-Offset", required = false) String timezoneOffset,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            Authentication authentication) {
        log.info("User {} requesting current glucose", authentication.getName());
        
        try {
            // Get user UUID
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            NightscoutEntryDto currentGlucose = nightScoutIntegration.getCurrentGlucose(userId);
            log.info("Fetched current glucose from Nightscout");
            
            // Apply timezone offset from frontend if available and entry doesn't have utcOffset
            if (timezoneOffset != null && currentGlucose != null) {
                try {
                    int offsetMinutes = Integer.parseInt(timezoneOffset);
                    // Convert from JavaScript offset (minutes behind UTC) to standard offset (minutes ahead of UTC)
                    int utcOffset = -offsetMinutes;
                    log.info("Applying frontend timezone offset {} minutes (UTC offset: {}) to current glucose entry", 
                            offsetMinutes, utcOffset);
                    
                    if (currentGlucose.getUtcOffset() == null) {
                        currentGlucose.setUtcOffset(utcOffset);
                        log.debug("Set utcOffset to {} for current glucose entry {}", utcOffset, currentGlucose.getId());
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid timezone offset format: {}", timezoneOffset);
                }
            }
            
            return ResponseEntity.ok(currentGlucose);
        } catch (Exception e) {
            log.error("Failed to fetch current glucose", e);
            throw new RuntimeException("Failed to fetch current glucose: " + rootCauseMessage(e), e);
        }
    }
    
    @GetMapping("/entries/date-range")
    public ResponseEntity<List<NightscoutEntryDto>> getGlucoseEntriesByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(value = "useStored", defaultValue = "false") boolean useStored,
            @RequestHeader(value = "X-Timezone-Offset", required = false) String timezoneOffset,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            Authentication authentication) {
        
        log.info("User {} requesting glucose entries from {} to {} (useStored: {})", 
                authentication.getName(), startDate, endDate, useStored);
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        if (useStored) {
            List<NightscoutEntryDto> storedEntries = chartDataService.getChartDataAsEntries(userId);
            applyTimezoneOffsetToEntries(storedEntries, timezoneOffset);
            log.info("Returning {} stored entries for user {}", storedEntries.size(), authentication.getName());
            return ResponseEntity.ok(storedEntries);
        }

        Instant startInstant = startDate.atZone(java.time.ZoneId.systemDefault()).toInstant();
        Instant endInstant = endDate.atZone(java.time.ZoneId.systemDefault()).toInstant();

        try {
            List<NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntriesByDate(userId, startInstant, endInstant);
            log.info("Fetched {} entries from Nightscout", entries.size());

            applyTimezoneOffsetToEntries(entries, timezoneOffset);

            chartDataService.storeChartDataAsync(userId, entries);
            return ResponseEntity.ok(entries);
        } catch (Exception e) {
            log.error("Failed to fetch glucose entries by date", e);
            throw new RuntimeException("Failed to fetch glucose data: " + rootCauseMessage(e), e);
        }
    }
    
    @GetMapping("/chart-data")
    public ResponseEntity<List<NightscoutEntryDto>> getStoredChartData(
            @RequestParam(value = "count", defaultValue = "100") int count,
            Authentication authentication) {
        log.info("User {} requesting {} stored chart data entries", authentication.getName(), count);
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        // Get stored chart data
        List<NightscoutEntryDto> chartData = chartDataService.getChartDataAsEntries(userId);
        
        // Most recent `count` points (stored ascending by time)
        if (chartData.size() > count) {
            int from = Math.max(0, chartData.size() - count);
            chartData = chartData.subList(from, chartData.size());
        }
        
        log.info("Retrieved {} stored chart data entries for user {}", chartData.size(), authentication.getName());
        return ResponseEntity.ok(chartData);
    }
    
    @DeleteMapping("/chart-data")
    public ResponseEntity<String> clearStoredChartData(Authentication authentication) {
        log.info("User {} requesting to clear stored chart data", authentication.getName());
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        // Clear stored chart data
        chartDataService.clearChartData(userId);
        
        log.info("Cleared stored chart data for user {}", authentication.getName());
        return ResponseEntity.ok("Chart data cleared successfully");
    }
    
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck(Authentication authentication) {
        log.info("User {} checking Nightscout proxy health", authentication.getName());
        return ResponseEntity.ok("Nightscout proxy is healthy");
    }
}
