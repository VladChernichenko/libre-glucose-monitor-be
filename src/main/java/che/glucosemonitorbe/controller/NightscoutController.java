package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.dto.NightscoutDeviceStatusDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.service.NightscoutChartDataService;
import che.glucosemonitorbe.service.NightscoutConfigService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.domain.NightscoutConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/nightscout")
@RequiredArgsConstructor
public class NightscoutController {
    
    private final NightScoutIntegration nightScoutIntegration;
    private final NightscoutChartDataService chartDataService;
    private final UserService userService;
    private final NightscoutConfigService configService;
    
    @GetMapping("/entries")
    public ResponseEntity<List<NightscoutEntryDto>> getGlucoseEntries(
            @RequestParam(value = "count", defaultValue = "100") int count,
            @RequestParam(value = "useStored", defaultValue = "false") boolean useStored,
            Authentication authentication) {
        
        log.info("User {} requesting {} glucose entries (useStored: {})", 
                authentication.getName(), count, useStored);
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        if (useStored) {
            // Return stored chart data from database
            List<NightscoutEntryDto> storedEntries = chartDataService.getChartDataAsEntries(userId);
            log.info("Returning {} stored entries for user {}", storedEntries.size(), authentication.getName());
            return ResponseEntity.ok(storedEntries);
        } else {
            // Try to use user's Nightscout configuration first
            Optional<NightscoutConfig> userConfig = configService.getConfigForApiCalls(userId);
            List<NightscoutEntryDto> entries;
            
            if (userConfig.isPresent()) {
                // Use user's Nightscout configuration
                entries = nightScoutIntegration.getGlucoseEntries(count, userConfig.get());
                configService.markAsUsed(userId);
                log.info("Fetched {} entries from user's Nightscout: {}", entries.size(), userConfig.get().getNightscoutUrl());
            } else {
                // Fallback to default Nightscout configuration
                entries = nightScoutIntegration.getGlucoseEntries(count);
                log.info("Fetched {} entries from default Nightscout configuration", entries.size());
            }
            
            // Store in database (100 fixed rows per user)
            if (!entries.isEmpty()) {
                chartDataService.storeChartData(userId, entries);
                log.info("Stored {} entries in database for user {}", entries.size(), authentication.getName());
            }
            
            return ResponseEntity.ok(entries);
        }
    }
    
    @GetMapping("/entries/current")
    public ResponseEntity<NightscoutEntryDto> getCurrentGlucose(Authentication authentication) {
        log.info("User {} requesting current glucose", authentication.getName());
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        // Try to use user's Nightscout configuration first
        Optional<NightscoutConfig> userConfig = configService.getConfigForApiCalls(userId);
        NightscoutEntryDto currentGlucose;
        
        if (userConfig.isPresent()) {
            // Use user's Nightscout configuration
            currentGlucose = nightScoutIntegration.getCurrentGlucose(userConfig.get());
            configService.markAsUsed(userId);
            log.info("Fetched current glucose from user's Nightscout: {}", userConfig.get().getNightscoutUrl());
        } else {
            // Fallback to default Nightscout configuration
            currentGlucose = nightScoutIntegration.getCurrentGlucose();
            log.info("Fetched current glucose from default Nightscout configuration");
        }
        
        return ResponseEntity.ok(currentGlucose);
    }
    
    @GetMapping("/entries/date-range")
    public ResponseEntity<List<NightscoutEntryDto>> getGlucoseEntriesByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(value = "useStored", defaultValue = "false") boolean useStored,
            Authentication authentication) {
        
        log.info("User {} requesting glucose entries from {} to {} (useStored: {})", 
                authentication.getName(), startDate, endDate, useStored);
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        if (useStored) {
            // Return stored chart data from database
            List<NightscoutEntryDto> storedEntries = chartDataService.getChartDataAsEntries(userId);
            log.info("Returning {} stored entries for user {}", storedEntries.size(), authentication.getName());
            return ResponseEntity.ok(storedEntries);
        } else {
            // Use system default timezone instead of hardcoded UTC
            Instant startInstant = startDate.atZone(java.time.ZoneId.systemDefault()).toInstant();
            Instant endInstant = endDate.atZone(java.time.ZoneId.systemDefault()).toInstant();
            
            // Try to use user's Nightscout configuration first
            Optional<NightscoutConfig> userConfig = configService.getConfigForApiCalls(userId);
            List<NightscoutEntryDto> entries;
            
            if (userConfig.isPresent()) {
                // Use user's Nightscout configuration
                entries = nightScoutIntegration.getGlucoseEntriesByDate(startInstant, endInstant, userConfig.get());
                configService.markAsUsed(userId);
                log.info("Fetched {} entries from user's Nightscout: {}", entries.size(), userConfig.get().getNightscoutUrl());
            } else {
                // Fallback to default Nightscout configuration
                entries = nightScoutIntegration.getGlucoseEntriesByDate(startInstant, endInstant);
                log.info("Fetched {} entries from default Nightscout configuration", entries.size());
            }
            
            // Store in database (100 fixed rows per user)
            if (!entries.isEmpty()) {
                chartDataService.storeChartData(userId, entries);
                log.info("Stored {} entries from date range in database for user {}", entries.size(), authentication.getName());
            }
            
            return ResponseEntity.ok(entries);
        }
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
    public ResponseEntity<List<NightscoutEntryDto>> getStoredChartData(
            @RequestParam(value = "count", defaultValue = "100") int count,
            Authentication authentication) {
        log.info("User {} requesting {} stored chart data entries", authentication.getName(), count);
        
        // Get user UUID
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        
        // Get stored chart data
        List<NightscoutEntryDto> chartData = chartDataService.getChartDataAsEntries(userId);
        
        // Limit to requested count
        if (chartData.size() > count) {
            chartData = chartData.subList(0, count);
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
