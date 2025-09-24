package che.glucosemonitorbe.nightscout;

import che.glucosemonitorbe.domain.NightscoutConfig;
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
    
    public List<NightscoutEntryDto> getGlucoseEntries(int count, NightscoutConfig userConfig) {
        try {
            log.info("Fetching {} glucose entries from user's Nightscout: {}", count, userConfig.getNightscoutUrl());
            log.info("User config - URL: {}, API Secret present: {}, API Token present: {}", 
                    userConfig.getNightscoutUrl(), 
                    userConfig.getApiSecret() != null && !userConfig.getApiSecret().isEmpty(),
                    userConfig.getApiToken() != null && !userConfig.getApiToken().isEmpty());
            
            // Build the URL
            String url = userConfig.getNightscoutUrl().replaceAll("/$", "") + "/api/v2/entries.json?count=" + count;
            log.info("Final URL: {}", url);
            
            // Create headers
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Accept", "application/json");
            
            // Add authentication headers
            if (userConfig.getApiSecret() != null && !userConfig.getApiSecret().isEmpty()) {
                String hashedSecret = hashApiSecret(userConfig.getApiSecret());
                headers.set("api-secret", hashedSecret);
                log.info("Added api-secret header (hashed)");
            } else {
                log.warn("No API secret provided for user's Nightscout configuration");
            }
            if (userConfig.getApiToken() != null && !userConfig.getApiToken().isEmpty()) {
                headers.set("Authorization", "Bearer " + userConfig.getApiToken());
                log.info("Added Authorization header");
            } else {
                log.warn("No API token provided for user's Nightscout configuration");
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
            log.error("Failed to fetch glucose entries from user's Nightscout: {}", userConfig.getNightscoutUrl(), e);
            throw new RuntimeException("Failed to fetch glucose data from user's Nightscout", e);
        }
    }
    
    public List<NightscoutEntryDto> getGlucoseEntriesByDate(Instant startDate, Instant endDate, NightscoutConfig userConfig) {
        try {
            String startDateStr = startDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            String endDateStr = endDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            
            log.info("Fetching glucose entries from {} to {} from user's Nightscout: {}", startDateStr, endDateStr, userConfig.getNightscoutUrl());
            
            // Build the URL
            String url = userConfig.getNightscoutUrl().replaceAll("/$", "") + 
                        "/api/v2/entries.json?find[date][$gte]=" + startDateStr + 
                        "&find[date][$lte]=" + endDateStr;
            
            // Create headers
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Accept", "application/json");
            
            // Add authentication headers
            if (userConfig.getApiSecret() != null && !userConfig.getApiSecret().isEmpty()) {
                String hashedSecret = hashApiSecret(userConfig.getApiSecret());
                headers.set("api-secret", hashedSecret);
            }
            if (userConfig.getApiToken() != null && !userConfig.getApiToken().isEmpty()) {
                headers.set("Authorization", "Bearer " + userConfig.getApiToken());
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
            log.error("Failed to fetch glucose entries by date from user's Nightscout: {}", userConfig.getNightscoutUrl(), e);
            throw new RuntimeException("Failed to fetch glucose data from user's Nightscout", e);
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
