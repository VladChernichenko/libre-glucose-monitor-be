package che.glucosemonitorbe.nightscout;

import che.glucosemonitorbe.config.NightscoutConfig;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NightScoutIntegration {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final NightscoutConfig nightscoutConfig;
    
    public List<NightscoutEntryDto> getGlucoseEntries(int count) {
        try {
            if (!nightscoutConfig.isConfigured()) {
                throw new RuntimeException("Nightscout is not configured. Please set NIGHTSCOUT_URL and API credentials.");
            }
            
            log.info("Fetching {} glucose entries from Nightscout: {}", count, nightscoutConfig.getUrl());
            log.info("Config - URL: {}, API Secret present: {}, API Token present: {}", 
                    nightscoutConfig.getUrl(), 
                    nightscoutConfig.getApiSecret() != null && !nightscoutConfig.getApiSecret().isEmpty(),
                    nightscoutConfig.getApiToken() != null && !nightscoutConfig.getApiToken().isEmpty());
            
            // Build the URL
            String url = nightscoutConfig.getUrl().replaceAll("/$", "") + "/api/v2/entries.json?count=" + count;
            log.info("Final URL: {}", url);
            
            // Create headers
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Accept", "application/json");
            
            // Add authentication headers
            if (nightscoutConfig.getApiSecret() != null && !nightscoutConfig.getApiSecret().isEmpty()) {
                String hashedSecret = hashApiSecret(nightscoutConfig.getApiSecret());
                headers.set("api-secret", hashedSecret);
                log.info("Added api-secret header (hashed)");
            } else {
                log.warn("No API secret provided for Nightscout configuration");
            }
            if (nightscoutConfig.getApiToken() != null && !nightscoutConfig.getApiToken().isEmpty()) {
                headers.set("Authorization", "Bearer " + nightscoutConfig.getApiToken());
                log.info("Added Authorization header");
            } else {
                log.warn("No API token provided for Nightscout configuration");
            }
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            // Make the request
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                return objectMapper.readValue(response.getBody(), new TypeReference<List<NightscoutEntryDto>>() {});
            } else {
                throw new RuntimeException("Nightscout API returned status: " + response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("Failed to fetch glucose entries from Nightscout: {}", nightscoutConfig.getUrl(), e);
            throw new RuntimeException("Failed to fetch glucose data from Nightscout", e);
        }
    }
    
    public List<NightscoutEntryDto> getGlucoseEntriesByDate(Instant startDate, Instant endDate) {
        try {
            if (!nightscoutConfig.isConfigured()) {
                throw new RuntimeException("Nightscout is not configured. Please set NIGHTSCOUT_URL and API credentials.");
            }
            
            String startDateStr = startDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            String endDateStr = endDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            
            log.info("Fetching glucose entries from {} to {} from Nightscout: {}", startDateStr, endDateStr, nightscoutConfig.getUrl());
            
            // Build the URL
            String url = nightscoutConfig.getUrl().replaceAll("/$", "") + 
                        "/api/v2/entries.json?find[date][$gte]=" + startDateStr + 
                        "&find[date][$lte]=" + endDateStr;
            
            // Create headers
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Accept", "application/json");
            
            // Add authentication headers
            if (nightscoutConfig.getApiSecret() != null && !nightscoutConfig.getApiSecret().isEmpty()) {
                String hashedSecret = hashApiSecret(nightscoutConfig.getApiSecret());
                headers.set("api-secret", hashedSecret);
            }
            if (nightscoutConfig.getApiToken() != null && !nightscoutConfig.getApiToken().isEmpty()) {
                headers.set("Authorization", "Bearer " + nightscoutConfig.getApiToken());
            }
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            // Make the request
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                return objectMapper.readValue(response.getBody(), new TypeReference<List<NightscoutEntryDto>>() {});
            } else {
                throw new RuntimeException("Nightscout API returned status: " + response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("Failed to fetch glucose entries by date from Nightscout: {}", nightscoutConfig.getUrl(), e);
            throw new RuntimeException("Failed to fetch glucose data from Nightscout", e);
        }
    }
    
    public NightscoutEntryDto getCurrentGlucose() {
        try {
            List<NightscoutEntryDto> entries = getGlucoseEntries(1);
            return entries.isEmpty() ? null : entries.get(0);
        } catch (Exception e) {
            log.error("Failed to fetch current glucose from Nightscout: {}", nightscoutConfig.getUrl(), e);
            throw new RuntimeException("Failed to fetch current glucose from Nightscout", e);
        }
    }
    
    /**
     * Hash the API secret using SHA-1 as required by Nightscout
     */
    private String hashApiSecret(String apiSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(apiSecret.getBytes(StandardCharsets.UTF_8));
            
            // Convert byte array to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-1 algorithm not available", e);
            throw new RuntimeException("Failed to hash API secret", e);
        }
    }
    
}
