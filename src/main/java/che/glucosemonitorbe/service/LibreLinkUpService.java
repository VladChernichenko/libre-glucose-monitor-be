package che.glucosemonitorbe.service;

import che.glucosemonitorbe.circuitbreaker.CircuitBreaker;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerException;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.service.libre.LibreLinkUpClient;
import che.glucosemonitorbe.service.libre.LibreLinkUpRegionResolver;
import che.glucosemonitorbe.service.libre.LibreLinkUpResponseParser;
import che.glucosemonitorbe.service.libre.LibreLinkUpSessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.*;

/**
 * Orchestrates LibreLinkUp authentication and data retrieval (auth, connections, glucose graph,
 * profile, sensor info, alarms) with per-operation circuit breakers.
 *
 * <p>BE-M5 decomposition: transport (HTTP, headers, byte parsing) lives in {@link LibreLinkUpClient};
 * per-user session state in {@link LibreLinkUpSessionStore}; regional host routing in
 * {@link LibreLinkUpRegionResolver}; wire→domain mapping helpers in {@link LibreLinkUpResponseParser}.
 * This class coordinates those collaborators and owns the auth flow and JSON→DTO mapping.
 */
@Service
public class LibreLinkUpService {

    private static final Logger logger = LoggerFactory.getLogger(LibreLinkUpService.class);

    @Value("${libre.api.base-url:https://api-eu.libreview.io}")
    private String defaultBaseUrl;

    private final ObjectMapper objectMapper;
    private final CircuitBreakerManager circuitBreakerManager;
    private final LibreLinkUpClient client;
    private final LibreLinkUpSessionStore sessionStore;
    private final LibreLinkUpRegionResolver regionResolver;
    private final LibreLinkUpResponseParser responseParser;

    public LibreLinkUpService(CircuitBreakerManager circuitBreakerManager,
                              LibreLinkUpClient client,
                              LibreLinkUpSessionStore sessionStore,
                              LibreLinkUpRegionResolver regionResolver,
                              LibreLinkUpResponseParser responseParser) {
        this.objectMapper = new ObjectMapper();
        this.circuitBreakerManager = circuitBreakerManager;
        this.client = client;
        this.sessionStore = sessionStore;
        this.regionResolver = regionResolver;
        this.responseParser = responseParser;
    }

    /** Resolved base URL for userId (falls back to the configured default). */
    private String baseUrlFor(UUID userId) {
        return sessionStore.baseUrlOrDefault(userId, LibreLinkUpRegionResolver.normalizeBaseUrl(defaultBaseUrl));
    }

    /**
     * Authenticate with LibreLinkUp and store the resulting token per-user.
     * BE-1: credentials are keyed by userId — no shared singleton state.
     */
    public LibreAuthResponse authenticate(LibreAuthRequest authRequest, UUID userId) throws Exception {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-auth:" + userId);
        try {
            return circuitBreaker.execute(() -> {
                try {
                    // Locale-first routing: probe the locale-matched host before the configured default.
                    List<String> baseList = regionResolver.authBaseOrder(
                            authRequest.getLocale(), LibreLinkUpRegionResolver.normalizeBaseUrl(defaultBaseUrl));
                    ResponseEntity<byte[]> response = null;

                    for (int i = 0; i < baseList.size(); i++) {
                        String apiBase = baseList.get(i);
                        try {
                            logger.info("Authenticating with LibreLinkUp API at {}/llu/auth/login (password length={})",
                                    apiBase,
                                    authRequest.getPassword() != null ? authRequest.getPassword().length() : 0);
                            response = client.postLogin(apiBase, authRequest);
                            break;
                        } catch (HttpClientErrorException e) {
                            int code = e.getStatusCode().value();
                            if (LibreLinkUpRegionResolver.isRetryableAcrossHosts(code) && i < baseList.size() - 1) {
                                logger.warn("LibreLinkUp auth HTTP {} from {}, trying next regional host", code, apiBase);
                                continue;
                            }
                            throw client.authError(e);
                        }
                    }
                    if (response == null) {
                        throw new RuntimeException("LibreLinkUp authentication failed: no response from API hosts");
                    }

                    JsonNode jsonResponse = client.parse(response);
                    logger.info("LibreLinkUp auth response: {}", jsonResponse.toString());

                    throwIfApiError(jsonResponse, "LibreLinkUp authentication error: ");

                    JsonNode data = jsonResponse.get("data");
                    if (data != null && data.has("redirect") && data.get("redirect").asBoolean()) {
                        String region = data.has("region") ? data.get("region").asText() : "";
                        logger.warn("LibreLinkUp requires region-specific endpoint. Region: {}", region);
                        if (!region.isEmpty()) {
                            return authenticateWithRegion(authRequest, region, userId);
                        }
                    }

                    JsonNode authTicket = jsonResponse.get("data") != null
                            ? jsonResponse.get("data").get("authTicket")
                            : jsonResponse.get("authTicket");
                    String token = null;
                    Long expires = null;
                    if (authTicket != null) {
                        token = authTicket.has("token") ? authTicket.get("token").asText() : null;
                        expires = authTicket.has("expires") ? authTicket.get("expires").asLong()
                                : System.currentTimeMillis() + (24 * 60 * 60 * 1000);
                    }

                    if (token != null) {
                        if (userId != null) {
                            storeSession(userId, token, authRequest.getLocale(), data, regionResolver.localeToBaseUrl(authRequest.getLocale()));
                        }
                        logger.info("Successfully authenticated with LibreLinkUp for user {}, token expires: {}", userId, expires);
                        return new LibreAuthResponse(token, expires);
                    } else {
                        logger.error("No authentication token in response. Full response: {}", jsonResponse.toString());
                        throw new RuntimeException("No authentication token received from LibreLinkUp. Response: " + jsonResponse.toString());
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    logger.error("LibreLinkUp authentication failed: {}", e.getMessage());
                    throw new RuntimeException("LibreLinkUp authentication failed: " + e.getMessage(), e);
                }
            });
        } catch (CircuitBreakerException e) {
            logger.warn("LibreLinkUp authentication circuit breaker is OPEN");
            throw new RuntimeException(
                    "LibreLinkUp authentication service is temporarily unavailable after repeated failures. Try again in about one minute.",
                    e);
        }
    }

    /** Authenticate with a region-specific endpoint. Stores credentials per userId. */
    private LibreAuthResponse authenticateWithRegion(LibreAuthRequest authRequest, String region, UUID userId) throws Exception {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-auth-region:" + userId);
        try {
            return circuitBreaker.execute(() -> {
                try {
                    String regionBaseUrl = regionResolver.regionBaseUrl(region);
                    logger.info("Authenticating with region-specific LibreLinkUp API at {}/llu/auth/login", regionBaseUrl);
                    ResponseEntity<byte[]> response;
                    try {
                        response = client.postLogin(regionBaseUrl, authRequest);
                    } catch (HttpClientErrorException e) {
                        throw client.authError(e);
                    }

                    JsonNode jsonResponse = client.parse(response);
                    logger.info("Region-specific auth response: {}", jsonResponse.toString());

                    JsonNode data = jsonResponse.get("data");
                    JsonNode authTicket = data != null ? data.get("authTicket") : null;
                    String token = null;
                    Long expires = null;
                    if (authTicket != null) {
                        token = authTicket.has("token") ? authTicket.get("token").asText() : null;
                        expires = authTicket.has("expires") ? authTicket.get("expires").asLong()
                                : System.currentTimeMillis() + (24 * 60 * 60 * 1000);
                    }

                    if (token != null) {
                        if (userId != null) {
                            // Region login always pins the resolved region host.
                            storeSession(userId, token, authRequest.getLocale(), data, regionBaseUrl);
                            logger.info("Stored region {} base URL for user {}", region, userId);
                        }
                        logger.info("Successfully authenticated with region {} endpoint for user {}, token expires: {}", region, userId, expires);
                        return new LibreAuthResponse(token, expires);
                    } else {
                        logger.error("No token in region-specific response: {}", jsonResponse.toString());
                        throw new RuntimeException("No authentication token in region-specific response");
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    logger.error("Region-specific LibreLinkUp authentication failed: {}", e.getMessage());
                    throw new RuntimeException("Region-specific LibreLinkUp authentication failed: " + e.getMessage(), e);
                }
            });
        } catch (CircuitBreakerException e) {
            logger.warn("Region-specific LibreLinkUp authentication circuit breaker is OPEN");
            throw new RuntimeException(
                    "LibreLinkUp authentication service is temporarily unavailable after repeated failures. Try again in about one minute.",
                    e);
        }
    }

    /**
     * Persist the per-user session from a successful login: token, locale, regional base URL, and the
     * SHA-256(user.id) account-id hash required since Nov 2024.
     *
     * @param baseUrlOverride base URL to pin for this user, or {@code null} to leave it at the default
     *                        (used by locale-derived routing on the primary login path)
     */
    private void storeSession(UUID userId, String token, String locale, JsonNode data, String baseUrlOverride) {
        sessionStore.putToken(userId, token);
        if (locale != null && !locale.isBlank()) {
            sessionStore.putLocale(userId, locale);
        }
        if (baseUrlOverride != null) {
            sessionStore.putBaseUrl(userId, baseUrlOverride);
        }
        JsonNode userNode = data != null ? data.get("user") : null;
        if (userNode != null && userNode.has("id")) {
            String libreUserId = userNode.get("id").asText();
            if (!libreUserId.isBlank()) {
                sessionStore.putAccountId(userId, LibreLinkUpResponseParser.sha256Hex(libreUserId));
                logger.info("Stored account-id hash for user {}", userId);
            }
        }
    }

    /**
     * Get LibreLinkUp connections for a user.
     * BE-1: per-user token/baseUrl. BE-2: unwraps the {"data":[...]} envelope.
     */
    public List<LibreConnection> getConnections(UUID userId) throws Exception {
        requireAuthenticated(userId);
        String baseUrl = baseUrlFor(userId);
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-connections:" + userId);

        return circuitBreaker.executeWithFallback(
            () -> {
                try {
                    String url = baseUrl + "/llu/connections";
                    logger.info("Fetching LibreLinkUp connections from {}", url);
                    JsonNode jsonResponse = client.authenticatedGet(userId, url);
                    throwIfApiError(jsonResponse, "LibreLinkUp connections error: ");

                    List<LibreConnection> connections = responseParser.toConnections(jsonResponse);
                    logger.info("Successfully fetched {} LibreLinkUp connections for user {}", connections.size(), userId);
                    return connections;
                } catch (Exception e) {
                    logger.error("Failed to fetch LibreLinkUp connections for user {}: {}", userId, e.getMessage());
                    throw new RuntimeException("Failed to fetch LibreLinkUp connections: " + e.getMessage());
                }
            },
            () -> {
                logger.warn("LibreLinkUp connections circuit breaker is OPEN - returning empty list");
                return new ArrayList<>();
            }
        );
    }

    /** Get glucose data for a patient. BE-1: per-user credentials. */
    public LibreGlucoseData getGlucoseData(String patientId, int days, UUID userId) throws Exception {
        requireAuthenticated(userId);
        String baseUrl = baseUrlFor(userId);
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-glucose-data:" + userId);

        return circuitBreaker.executeWithFallback(
            () -> {
                try {
                    String url = baseUrl + "/llu/connections/" + patientId + "/graph";
                    logger.info("Fetching LibreLinkUp glucose data for patient {} from {}", patientId, url);
                    JsonNode jsonResponse = client.authenticatedGet(userId, url);
                    throwIfApiError(jsonResponse, "LibreLinkUp glucose data error: ");

                    LibreGlucoseData glucoseData = responseParser.toGlucoseData(jsonResponse, patientId);
                    logger.info("Successfully fetched {} glucose readings for patient {}", glucoseData.getData().size(), patientId);
                    return glucoseData;
                } catch (Exception e) {
                    logger.error("Failed to fetch LibreLinkUp glucose data for patient {}: {}", patientId, e.getMessage());
                    throw new RuntimeException("Failed to fetch LibreLinkUp glucose data: " + e.getMessage());
                }
            },
            () -> {
                logger.warn("LibreLinkUp glucose data circuit breaker is OPEN - returning empty data");
                return new LibreGlucoseData(
                    patientId,
                    new ArrayList<>(),
                    Instant.now().minusSeconds(12 * 60 * 60).toString(),
                    Instant.now().toString(),
                    "mmol/L"
                );
            }
        );
    }

    /** Most recent reading for a patient. BE-1: per-user credentials. */
    public LibreGlucoseReading getCurrentGlucose(String patientId, UUID userId) throws Exception {
        LibreGlucoseData glucoseData = getGlucoseData(patientId, 1, userId);
        if (glucoseData.getData() != null && !glucoseData.getData().isEmpty()) {
            // LLU graphData is sorted oldest-first; return the last element (most recent).
            List<LibreGlucoseReading> readings = glucoseData.getData();
            return readings.get(readings.size() - 1);
        } else {
            throw new RuntimeException("No glucose data available for patient " + patientId);
        }
    }

    /**
     * Historical glucose data for a patient. Delegates to {@link #getGlucoseData} (same /graph
     * endpoint) and applies optional date filtering on the result.
     */
    public LibreGlucoseData getGlucoseHistory(String patientId, int days, String startDate, String endDate, UUID userId) throws Exception {
        LibreGlucoseData data = getGlucoseData(patientId, days, userId);
        if (startDate != null || endDate != null) {
            Instant start = startDate != null ? responseParser.parseTimestamp(startDate).toInstant() : Instant.EPOCH;
            Instant end   = endDate   != null ? responseParser.parseTimestamp(endDate).toInstant()   : Instant.now();
            List<LibreGlucoseReading> filtered = data.getData().stream()
                .filter(r -> r.getTimestamp() != null)
                .filter(r -> !r.getTimestamp().toInstant().isBefore(start) &&
                             !r.getTimestamp().toInstant().isAfter(end))
                .collect(java.util.stream.Collectors.toList());
            return new LibreGlucoseData(
                data.getPatientId(),
                filtered,
                startDate != null ? startDate : data.getStartDate(),
                endDate   != null ? endDate   : data.getEndDate(),
                data.getUnit()
            );
        }
        return data;
    }

    /**
     * Extract sensor information from the activeSensors node of the /graph response.
     * Returns null-safe sensor info when no active sensor is present.
     */
    public LibreSensorInfo getSensorInfo(String patientId, UUID userId) throws Exception {
        requireAuthenticated(userId);
        String baseUrl = baseUrlFor(userId);
        CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker("libre-sensor-info:" + userId);

        return cb.executeWithFallback(
            () -> {
                try {
                    String url = baseUrl + "/llu/connections/" + patientId + "/graph";
                    logger.info("Fetching sensor info for patient {} from {}", patientId, url);
                    JsonNode json = client.authenticatedGet(userId, url);
                    return responseParser.toSensorInfo(json, patientId);
                } catch (Exception e) {
                    logger.error("Failed to fetch sensor info for patient {}: {}", patientId, e.getMessage());
                    throw new RuntimeException("Failed to fetch sensor info: " + e.getMessage(), e);
                }
            },
            () -> {
                logger.warn("LibreLinkUp sensor info circuit breaker is OPEN");
                return responseParser.unknownSensorInfo();
            }
        );
    }

    /** True if the user has a stored LibreLinkUp token. BE-1: per-user. */
    public boolean isAuthenticated(UUID userId) {
        return sessionStore.isAuthenticated(userId);
    }

    /** Clear all LibreLinkUp session state for a user. BE-1: per-user. */
    public void logout(UUID userId) {
        sessionStore.clear(userId);
    }

    private void requireAuthenticated(UUID userId) {
        if (sessionStore.token(userId) == null) {
            throw new RuntimeException("Not authenticated with LibreLinkUp");
        }
    }

    /** Throw a RuntimeException prefixed with {@code prefix} if the response carries an {@code {"error":...}} node. */
    private void throwIfApiError(JsonNode json, String prefix) {
        if (json.has("error")) {
            JsonNode error = json.get("error");
            String message = error.isObject() ? error.path("message").asText(error.toString()) : error.asText();
            throw new RuntimeException(prefix + message);
        }
    }
}
