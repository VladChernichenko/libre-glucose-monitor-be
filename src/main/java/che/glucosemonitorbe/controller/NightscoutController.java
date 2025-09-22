package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.dto.NightscoutDeviceStatusDto;
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
            Authentication authentication) {
        
        log.info("User {} requesting {} glucose entries", authentication.getName(), count);
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        // Fetch entries from Nightscout
        List<NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntries(count);
        
        // Store in database (100 fixed rows per user)
        if (!entries.isEmpty()) {
            chartDataService.storeChartData(userId, entries);
            log.info("Stored {} entries in database for user {}", entries.size(), authentication.getName());
        }
        
        return ResponseEntity.ok(entries);
    }
    
    @GetMapping("/entries/current")
    public ResponseEntity<NightscoutEntryDto> getCurrentGlucose(Authentication authentication) {
        log.info("User {} requesting current glucose", authentication.getName());
        NightscoutEntryDto currentGlucose = nightScoutIntegration.getCurrentGlucose();
        return ResponseEntity.ok(currentGlucose);
    }
    
    @GetMapping("/entries/date-range")
    public ResponseEntity<List<NightscoutEntryDto>> getGlucoseEntriesByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            Authentication authentication) {
        
        log.info("User {} requesting glucose entries from {} to {}", 
                authentication.getName(), startDate, endDate);
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        // Use system default timezone instead of hardcoded UTC
        Instant startInstant = startDate.atZone(java.time.ZoneId.systemDefault()).toInstant();
        Instant endInstant = endDate.atZone(java.time.ZoneId.systemDefault()).toInstant();
        
        List<NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntriesByDate(startInstant, endInstant);
        
        // Store in database (100 fixed rows per user)
        if (!entries.isEmpty()) {
            chartDataService.storeChartData(userId, entries);
            log.info("Stored {} entries from date range in database for user {}", entries.size(), authentication.getName());
        }
        
        return ResponseEntity.ok(entries);
    }
    
    @GetMapping("/device-status")
    public ResponseEntity<List<NightscoutDeviceStatusDto>> getDeviceStatus(
            @RequestParam(value = "count", defaultValue = "1") int count,
            Authentication authentication) {
        
        log.info("User {} requesting {} device status entries", authentication.getName(), count);
        List<NightscoutDeviceStatusDto> deviceStatus = nightScoutIntegration.getDeviceStatus(count);
        return ResponseEntity.ok(deviceStatus);
    }
    
    @GetMapping("/chart-data")
    public ResponseEntity<List<NightscoutEntryDto>> getStoredChartData(Authentication authentication) {
        log.info("User {} requesting stored chart data", authentication.getName());
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        // Get stored chart data
        List<NightscoutEntryDto> chartData = chartDataService.getChartDataAsEntries(userId);
        
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
