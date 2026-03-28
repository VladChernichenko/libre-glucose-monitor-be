package che.glucosemonitorbe.nightscout;

import che.glucosemonitorbe.circuitbreaker.CircuitBreaker;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.service.UserDataSourceConfigService;
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
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NightScoutIntegration {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final UserDataSourceConfigService userDataSourceConfigService;
    private final CircuitBreakerManager circuitBreakerManager;
    
    public List<NightscoutEntryDto> getGlucoseEntries(UUID userId, int count) {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("nightscout-entries");
        
        return circuitBreaker.executeWithFallback(
            () -> {
                try {
                    // Get user-specific Nightscout configuration
                    Optional<UserDataSourceConfig> nightscoutConfig = userDataSourceConfigService.getActiveConfigEntity(userId, UserDataSourceConfig.DataSourceType.NIGHTSCOUT);
                    
                    if (nightscoutConfig.isEmpty()) {
                        throw new RuntimeException("No active Nightscout configuration found for user. Please configure Nightscout in Data Source settings.");
                    }
                    
                    UserDataSourceConfig config = nightscoutConfig.get();
                    String url = config.getNightscoutUrl();
                    String apiSecret = config.getNightscoutApiSecret();
                    String apiToken = config.getNightscoutApiToken();
                    
                    if (url == null || url.trim().isEmpty()) {
                        throw new RuntimeException("Nightscout URL is not configured. Please set the URL in Data Source settings.");
                    }
                    
                    log.info("Fetching {} glucose entries from Nightscout: {}", count, url);
                    log.info("Config - URL: {}, API Secret present: {}, API Token present: {}", 
                            url, 
                            apiSecret != null && !apiSecret.isEmpty(),
                            apiToken != null && !apiToken.isEmpty());
                    
                    // Build the URL
                    String fullUrl = url.replaceAll("/$", "") + "/api/v2/entries.json?count=" + count;
                    log.info("Final URL: {}", fullUrl);
                    
                    // Create headers
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Content-Type", "application/json");
                    headers.set("Accept", "application/json");
                    
                    // Add authentication headers
                    if (apiSecret != null && !apiSecret.isEmpty()) {
                        String hashedSecret = hashApiSecret(apiSecret);
                        headers.set("api-secret", hashedSecret);
                        log.info("Added api-secret header (hashed)");
                    } else {
                        log.warn("No API secret provided for Nightscout configuration");
                    }
                    if (apiToken != null && !apiToken.isEmpty()) {
                        headers.set("Authorization", "Bearer " + apiToken);
                        log.info("Added Authorization header");
                    } else {
                        log.warn("No API token provided for Nightscout configuration");
                    }
                    
                    HttpEntity<String> entity = new HttpEntity<>(headers);
                    
                    // Make the request
                    ResponseEntity<String> response = restTemplate.exchange(fullUrl, HttpMethod.GET, entity, String.class);
                    
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return objectMapper.readValue(response.getBody(), new TypeReference<List<NightscoutEntryDto>>() {});
                    } else {
                        throw new RuntimeException("Nightscout API returned status: " + response.getStatusCode());
                    }
                    
                } catch (Exception e) {
                    log.error("Failed to fetch glucose entries from Nightscout for user {}: {}", userId, e.getMessage());
                    throw new RuntimeException("Failed to fetch glucose data from Nightscout", e);
                }
            },
            () -> {
                log.warn("Nightscout entries circuit breaker is OPEN - returning empty list");
                return List.of();
            }
        );
    }
    
    public List<NightscoutEntryDto> getGlucoseEntriesByDate(UUID userId, Instant startDate, Instant endDate) {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("nightscout-entries-by-date");
        
        return circuitBreaker.executeWithFallback(
            () -> {
                try {
                    // Get user-specific Nightscout configuration
                    Optional<UserDataSourceConfig> nightscoutConfig = userDataSourceConfigService.getActiveConfigEntity(userId, UserDataSourceConfig.DataSourceType.NIGHTSCOUT);
                    
                    if (nightscoutConfig.isEmpty()) {
                        throw new RuntimeException("No active Nightscout configuration found for user. Please configure Nightscout in Data Source settings.");
                    }
                    
                    UserDataSourceConfig config = nightscoutConfig.get();
                    String url = config.getNightscoutUrl();
                    String apiSecret = config.getNightscoutApiSecret();
                    String apiToken = config.getNightscoutApiToken();
                    
                    if (url == null || url.trim().isEmpty()) {
                        throw new RuntimeException("Nightscout URL is not configured. Please set the URL in Data Source settings.");
                    }
                    
                    String startDateStr = startDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
                    String endDateStr = endDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
                    
                    log.info("Fetching glucose entries from {} to {} from Nightscout: {}", startDateStr, endDateStr, url);
                    
                    // Build the URL
                    String fullUrl = url.replaceAll("/$", "") + 
                                "/api/v2/entries.json?find[date][$gte]=" + startDateStr + 
                                "&find[date][$lte]=" + endDateStr;
                    log.info("Final URL: {}", fullUrl);
                    
                    // Create headers
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Content-Type", "application/json");
                    headers.set("Accept", "application/json");
                    
                    // Add authentication headers
                    if (apiSecret != null && !apiSecret.isEmpty()) {
                        String hashedSecret = hashApiSecret(apiSecret);
                        headers.set("api-secret", hashedSecret);
                        log.info("Added api-secret header (hashed)");
                    } else {
                        log.warn("No API secret provided for Nightscout configuration");
                    }
                    if (apiToken != null && !apiToken.isEmpty()) {
                        headers.set("Authorization", "Bearer " + apiToken);
                        log.info("Added Authorization header");
                    } else {
                        log.warn("No API token provided for Nightscout configuration");
                    }
                    
                    HttpEntity<String> entity = new HttpEntity<>(headers);
                    
                    // Make the request
                    ResponseEntity<String> response = restTemplate.exchange(fullUrl, HttpMethod.GET, entity, String.class);
                    
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return objectMapper.readValue(response.getBody(), new TypeReference<List<NightscoutEntryDto>>() {});
                    } else {
                        throw new RuntimeException("Nightscout API returned status: " + response.getStatusCode());
                    }
                    
                } catch (Exception e) {
                    log.error("Failed to fetch glucose entries by date from Nightscout for user {}: {}", userId, e.getMessage());
                    throw new RuntimeException("Failed to fetch glucose data from Nightscout", e);
                }
            },
            () -> {
                log.warn("Nightscout entries by date circuit breaker is OPEN - returning empty list");
                return List.of();
            }
        );
    }
    
    public NightscoutEntryDto getCurrentGlucose(UUID userId) {
        try {
            List<NightscoutEntryDto> entries = getGlucoseEntries(userId, 1);
            return entries.isEmpty() ? null : entries.get(0);
        } catch (Exception e) {
            log.error("Failed to fetch current glucose from Nightscout for user {}", userId, e);
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
