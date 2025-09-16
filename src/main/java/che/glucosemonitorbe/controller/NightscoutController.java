package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.dto.NightscoutDeviceStatusDto;
import che.glucosemonitorbe.dto.NightscoutAverageDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/nightscout")
@RequiredArgsConstructor
public class NightscoutController {
    
    private final NightScoutIntegration nightScoutIntegration;
    
    @GetMapping("/entries")
    public ResponseEntity<List<NightscoutEntryDto>> getGlucoseEntries(
            @RequestParam(value = "count", defaultValue = "100") int count,
            Authentication authentication) {
        
        log.info("User {} requesting {} glucose entries", authentication.getName(), count);
        List<NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntries(count);
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
        
        Instant startInstant = startDate.toInstant(ZoneOffset.UTC);
        Instant endInstant = endDate.toInstant(ZoneOffset.UTC);
        
        List<NightscoutEntryDto> entries = nightScoutIntegration.getGlucoseEntriesByDate(startInstant, endInstant);
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
    
    @GetMapping("/average/24h")
    public ResponseEntity<NightscoutAverageDto> get24HourAverage(Authentication authentication) {
        log.info("User {} requesting 24-hour average glucose", authentication.getName());
        NightscoutAverageDto average = nightScoutIntegration.get24HourAverage();
        return ResponseEntity.ok(average);
    }
    
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck(Authentication authentication) {
        log.info("User {} checking Nightscout proxy health", authentication.getName());
        return ResponseEntity.ok("Nightscout proxy is healthy");
    }
}
