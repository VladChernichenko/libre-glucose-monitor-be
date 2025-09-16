package che.glucosemonitorbe.nightscout;

import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.dto.NightscoutDeviceStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NightScoutIntegration {
    
    private final NightScoutClient nightScoutClient;
    
    @Value("${nightscout.api-secret:}")
    private String apiSecret;
    
    @Value("${nightscout.token:}")
    private String token;
    
    public List<NightscoutEntryDto> getGlucoseEntries(int count) {
        try {
            log.info("Fetching {} glucose entries from Nightscout", count);
            return nightScoutClient.getGlucoseEntries(count, apiSecret, formatToken());
        } catch (Exception e) {
            log.error("Failed to fetch glucose entries from Nightscout", e);
            throw new RuntimeException("Failed to fetch glucose data from Nightscout", e);
        }
    }
    
    public List<NightscoutEntryDto> getGlucoseEntriesByDate(Instant startDate, Instant endDate) {
        try {
            String startDateStr = startDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            String endDateStr = endDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            
            log.info("Fetching glucose entries from {} to {}", startDateStr, endDateStr);
            return nightScoutClient.getGlucoseEntriesByDate(startDateStr, endDateStr, apiSecret, formatToken());
        } catch (Exception e) {
            log.error("Failed to fetch glucose entries by date from Nightscout", e);
            throw new RuntimeException("Failed to fetch glucose data from Nightscout", e);
        }
    }
    
    public NightscoutEntryDto getCurrentGlucose() {
        try {
            List<NightscoutEntryDto> entries = getGlucoseEntries(1);
            return entries.isEmpty() ? null : entries.get(0);
        } catch (Exception e) {
            log.error("Failed to fetch current glucose from Nightscout", e);
            throw new RuntimeException("Failed to fetch current glucose from Nightscout", e);
        }
    }
    
    public List<NightscoutDeviceStatusDto> getDeviceStatus(int count) {
        try {
            log.info("Fetching {} device status entries from Nightscout", count);
            return nightScoutClient.getDeviceStatus(count, apiSecret, formatToken());
        } catch (Exception e) {
            log.error("Failed to fetch device status from Nightscout", e);
            throw new RuntimeException("Failed to fetch device status from Nightscout", e);
        }
    }
    
    private String formatToken() {
        return token != null && !token.isEmpty() ? "Bearer " + token : null;
    }
}
