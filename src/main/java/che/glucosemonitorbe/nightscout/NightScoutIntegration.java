package che.glucosemonitorbe.nightscout;

import che.glucosemonitorbe.circuitbreaker.CircuitBreaker;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.config.CacheConfig;
import che.glucosemonitorbe.dto.NightscoutCredentials;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.dto.NightscoutTestResponseDto;
import che.glucosemonitorbe.service.UserDataSourceConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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
    
    /**
     * Cached for 30 s per (userId, count). At 1000 RPS this collapses thousands of identical
     * polls into one upstream Nightscout call — the single biggest protection against DoS-ing
     * user-hosted Nightscout instances (many of which are 1-dyno Heroku deployments).
     *
     * The returned list is cached by reference; callers that need to mutate entries (e.g.
     * applying a request-specific timezone offset) MUST copy first — see the controller.
     */
    @Cacheable(value = CacheConfig.CACHE_NIGHTSCOUT_ENTRIES, key = "#userId + ':' + #count")
    public List<NightscoutEntryDto> getGlucoseEntries(UUID userId, int count) {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("nightscout-entries");

        return circuitBreaker.executeWithFallback(
            () -> {
                try {
                    Optional<NightscoutCredentials> nightscoutConfig = userDataSourceConfigService.getNightscoutCredentials(userId);

                    if (nightscoutConfig.isEmpty()) {
                        throw new RuntimeException("No active Nightscout configuration found for user. Please configure Nightscout in Data Source settings.");
                    }

                    NightscoutCredentials config = nightscoutConfig.get();
                    String url = config.url();
                    String apiSecret = config.apiSecret();
                    String apiToken = config.apiToken();

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
                    Optional<NightscoutCredentials> nightscoutConfig = userDataSourceConfigService.getNightscoutCredentials(userId);

                    if (nightscoutConfig.isEmpty()) {
                        throw new RuntimeException("No active Nightscout configuration found for user. Please configure Nightscout in Data Source settings.");
                    }

                    NightscoutCredentials config = nightscoutConfig.get();
                    String url = config.url();
                    String apiSecret = config.apiSecret();
                    String apiToken = config.apiToken();

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
     * Verify that a Nightscout base URL accepts API v2 entries with the given credentials (no DB config).
     */
    public NightscoutTestResponseDto probeNightscout(String baseUrl, String apiSecret, String apiToken) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return NightscoutTestResponseDto.builder()
                    .ok(false)
                    .message("Nightscout URL is required.")
                    .build();
        }
        String url = baseUrl.trim().replaceAll("/$", "");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return NightscoutTestResponseDto.builder()
                    .ok(false)
                    .message("URL must start with http:// or https://")
                    .build();
        }

        String fullUrl = url + "/api/v2/entries.json?count=1";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");

        String secret = apiSecret != null ? apiSecret.trim() : "";
        String token = apiToken != null ? apiToken.trim() : "";
        if (!secret.isEmpty()) {
            headers.set("api-secret", hashApiSecret(secret));
        }
        if (!token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(fullUrl, HttpMethod.GET, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return NightscoutTestResponseDto.builder()
                        .ok(false)
                        .message("Nightscout returned HTTP " + response.getStatusCode().value())
                        .build();
            }
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                return NightscoutTestResponseDto.builder()
                        .ok(false)
                        .message("Empty response from Nightscout.")
                        .build();
            }
            try {
                objectMapper.readValue(body, new TypeReference<List<NightscoutEntryDto>>() {});
            } catch (Exception parseEx) {
                log.debug("Nightscout test: non-JSON or invalid entries body: {}", parseEx.getMessage());
                return NightscoutTestResponseDto.builder()
                        .ok(false)
                        .message("Response was not valid Nightscout entries JSON.")
                        .build();
            }
            return NightscoutTestResponseDto.builder()
                    .ok(true)
                    .message("Connected successfully. Nightscout API returned valid entries data.")
                    .build();
        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code == 401 || code == 403) {
                return NightscoutTestResponseDto.builder()
                        .ok(false)
                        .message("Authentication failed (HTTP " + code + "). Check API secret or token.")
                        .build();
            }
            return NightscoutTestResponseDto.builder()
                    .ok(false)
                    .message("HTTP " + code + ": " + e.getStatusText())
                    .build();
        } catch (HttpServerErrorException e) {
            return NightscoutTestResponseDto.builder()
                    .ok(false)
                    .message("Nightscout server error (HTTP " + e.getStatusCode().value() + ").")
                    .build();
        } catch (ResourceAccessException e) {
            return NightscoutTestResponseDto.builder()
                    .ok(false)
                    .message("Could not reach Nightscout: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            log.warn("Nightscout connection test failed: {}", e.getMessage());
            return NightscoutTestResponseDto.builder()
                    .ok(false)
                    .message("Connection test failed: " + e.getMessage())
                    .build();
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
