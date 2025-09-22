package che.glucosemonitorbe.nightscout;

import che.glucosemonitorbe.domain.NightscoutConfig;
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
import java.util.Optional;

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
    
    public List<NightscoutEntryDto> getGlucoseEntries(int count, NightscoutConfig userConfig) {
        try {
            log.info("Fetching {} glucose entries from user's Nightscout: {}", count, userConfig.getNightscoutUrl());
            return nightScoutClient.getGlucoseEntries(count, userConfig.getApiSecret(), formatUserToken(userConfig));
        } catch (Exception e) {
            log.error("Failed to fetch glucose entries from user's Nightscout: {}", userConfig.getNightscoutUrl(), e);
            throw new RuntimeException("Failed to fetch glucose data from user's Nightscout", e);
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
    
    public List<NightscoutEntryDto> getGlucoseEntriesByDate(Instant startDate, Instant endDate, NightscoutConfig userConfig) {
        try {
            String startDateStr = startDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            String endDateStr = endDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            
            log.info("Fetching glucose entries from {} to {} from user's Nightscout: {}", startDateStr, endDateStr, userConfig.getNightscoutUrl());
            return nightScoutClient.getGlucoseEntriesByDate(startDateStr, endDateStr, userConfig.getApiSecret(), formatUserToken(userConfig));
        } catch (Exception e) {
            log.error("Failed to fetch glucose entries by date from user's Nightscout: {}", userConfig.getNightscoutUrl(), e);
            throw new RuntimeException("Failed to fetch glucose data from user's Nightscout", e);
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
    
    public NightscoutEntryDto getCurrentGlucose(NightscoutConfig userConfig) {
        try {
            List<NightscoutEntryDto> entries = getGlucoseEntries(1, userConfig);
            return entries.isEmpty() ? null : entries.get(0);
        } catch (Exception e) {
            log.error("Failed to fetch current glucose from user's Nightscout: {}", userConfig.getNightscoutUrl(), e);
            throw new RuntimeException("Failed to fetch current glucose from user's Nightscout", e);
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
    
    private String formatUserToken(NightscoutConfig userConfig) {
        return userConfig.getApiToken() != null && !userConfig.getApiToken().isEmpty() ? "Bearer " + userConfig.getApiToken() : null;
    }
}
