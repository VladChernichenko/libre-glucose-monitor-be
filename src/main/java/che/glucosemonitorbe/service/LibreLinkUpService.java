package che.glucosemonitorbe.service;

import che.glucosemonitorbe.circuitbreaker.CircuitBreaker;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerException;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.libre.LibreCredentialsRejectedException;
import che.glucosemonitorbe.service.libre.LibreLinkUpClient;
import che.glucosemonitorbe.service.libre.LibreLinkUpRegionResolver;
import che.glucosemonitorbe.service.libre.LibreLinkUpResponseParser;
import che.glucosemonitorbe.service.libre.LibreLinkUpSessionStore;
import che.glucosemonitorbe.service.libre.LibreRateLimitedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Orchestrates LibreLinkUp authentication and data retrieval (auth, connections, glucose graph,
 * profile, sensor info, alarms) with per-operation circuit breakers.
 *
 * <p>BE-M5 decomposition: transport (HTTP, headers, byte parsing) lives in {@link LibreLinkUpClient};
 * per-user session state in {@link LibreLinkUpSessionStore}; regional host routing in
 * {@link LibreLinkUpRegionResolver}; wire->domain mapping helpers in {@link LibreLinkUpResponseParser}.
 * This class coordinates those collaborators and owns the auth flow and JSON->DTO mapping.
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
    /** Stored LibreLinkUp logins, for silently logging in again when the in-memory session is gone. */
    private final UserDataSourceConfigRepository configRepository;
    private final ConcurrentHashMap<UUID, Object> reloginLocks = new ConcurrentHashMap<>();
    /** Sensor info is read from the same /graph call as glucose. One LibreLinkUp fetch per user per day. */
    static final Duration SENSOR_INFO_TTL = Duration.ofDays(1);
    private final ConcurrentHashMap<String, CachedSensor> sensorInfoCache = new ConcurrentHashMap<>();
    /** One /graph download at a time per user and patient, shared by glucose sync and sensor info. */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> graphInflight = new ConcurrentHashMap<>();
    /**
     * Last successful /graph body per user and patient. Every caller (sync queue, on-demand sync, watch,
     * history, sensor) reuses it inside the window, so LibreLinkUp sees at most one /graph call per window.
     */
    private final ConcurrentHashMap<String, CachedGraph> graphCache = new ConcurrentHashMap<>();
    @Value("${libre.api.graph-cache-ttl-seconds:120}")
    long graphCacheTtlSeconds = 120;
    /** Clock for the sensor and graph caches; replaced in tests. */
    Clock sensorClock = Clock.systemUTC();

    private record CachedSensor(LibreSensorInfo info, long fetchedAtMs) {}
    private record CachedGraph(JsonNode body, long fetchedAtMs) {}

    public LibreLinkUpService(CircuitBreakerManager circuitBreakerManager,
                              LibreLinkUpClient client,
                              LibreLinkUpSessionStore sessionStore,
                              LibreLinkUpRegionResolver regionResolver,
                              LibreLinkUpResponseParser responseParser,
                              UserDataSourceConfigRepository configRepository) {
        this.objectMapper = new ObjectMapper();
        this.circuitBreakerManager = circuitBreakerManager;
        this.client = client;
        this.sessionStore = sessionStore;
        this.regionResolver = regionResolver;
        this.responseParser = responseParser;
        this.configRepository = configRepository;
    }

    /** Resolved base URL for userId (falls back to the configured default). */
    private String baseUrlFor(UUID userId) {
        return sessionStore.baseUrlOrDefault(userId, LibreLinkUpRegionResolver.normalizeBaseUrl(defaultBaseUrl));
    }

    /**
     * Authenticate with LibreLinkUp and store the resulting token per-user.
     * BE-1: credentials are keyed by userId - no shared singleton state.
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
                    // Never log the body: it carries the live LibreLinkUp bearer token and account data.
                    logger.info("LibreLinkUp auth response received: status={}", jsonResponse.path("status").asText("n/a"));

                    throwIfCredentialsRejected(jsonResponse);
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
                            storeSession(userId, token, authTicket, authRequest.getLocale(), data, regionResolver.localeToBaseUrl(authRequest.getLocale()));
                        }
                        logger.info("Successfully authenticated with LibreLinkUp for user {}, token expires: {}", userId, expires);
                        return new LibreAuthResponse(token, expires);
                    } else {
                        logger.error("No authentication token in LibreLinkUp response: status={}", jsonResponse.path("status").asText("n/a"));
                        throw new RuntimeException("No authentication token received from LibreLinkUp");
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
                    logger.info("Region-specific auth response received: status={}", jsonResponse.path("status").asText("n/a"));
                    throwIfCredentialsRejected(jsonResponse);

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
                            storeSession(userId, token, authTicket, authRequest.getLocale(), data, regionBaseUrl);
                            logger.info("Stored region {} base URL for user {}", region, userId);
                        }
                        logger.info("Successfully authenticated with region {} endpoint for user {}, token expires: {}", region, userId, expires);
                        return new LibreAuthResponse(token, expires);
                    } else {
                        logger.error("No token in region-specific response: status={}", jsonResponse.path("status").asText("n/a"));
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
    private void storeSession(UUID userId, String token, JsonNode authTicket, String locale, JsonNode data,
                              String baseUrlOverride) {
        String accountId = null;
        JsonNode userNode = data != null ? data.get("user") : null;
        if (userNode != null && userNode.has("id")) {
            String libreUserId = userNode.get("id").asText();
            if (!libreUserId.isBlank()) {
                accountId = LibreLinkUpResponseParser.sha256Hex(libreUserId);
            }
        }
        sessionStore.saveLogin(userId, token, ticketExpiry(authTicket), locale, baseUrlOverride, accountId);
        logger.info("Stored LibreLinkUp session for user {} (account-id {})", userId, accountId != null ? "set" : "missing");
    }

    /**
     * {@code authTicket.expires} as an instant: LibreLinkUp sends epoch seconds; a value that is
     * already epoch millis is accepted too. Null when absent or not a positive number.
     */
    static Instant ticketExpiry(JsonNode authTicket) {
        if (authTicket == null || !authTicket.hasNonNull("expires") || !authTicket.get("expires").canConvertToLong()) {
            return null;
        }
        long v = authTicket.get("expires").asLong();
        if (v <= 0) {
            return null;
        }
        return v < 100_000_000_000L ? Instant.ofEpochSecond(v) : Instant.ofEpochMilli(v);
    }

    /**
     * Get LibreLinkUp connections for a user.
     * BE-1: per-user token/baseUrl. BE-2: unwraps the {"data":[...]} envelope.
     */
    public List<LibreConnection> getConnections(UUID userId) throws Exception {
        ensureAuthenticated(userId);
        String baseUrl = baseUrlFor(userId);
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-connections:" + userId);

        return circuitBreaker.executeWithFallback(
            () -> {
                try {
                    String url = baseUrl + "/llu/connections";
                    logger.info("Fetching LibreLinkUp connections from {}", url);
                    JsonNode jsonResponse = authenticatedGet(userId, url);
                    throwIfApiError(jsonResponse, "LibreLinkUp connections error: ");

                    List<LibreConnection> connections = responseParser.toConnections(jsonResponse);
                    logger.info("Successfully fetched {} LibreLinkUp connections for user {}", connections.size(), userId);
                    return connections;
                } catch (Exception e) {
                    logger.error("Failed to fetch LibreLinkUp connections for user {}: {}", userId, e.getMessage());
                    throw wrapFetchFailure("Failed to fetch LibreLinkUp connections: ", e);
                }
            },
            () -> {
                logger.warn("LibreLinkUp connections circuit breaker is OPEN - returning empty list");
                return new ArrayList<>();
            }
        );
    }

    /**
     * Wraps a fetch failure keeping the cause chain; an HTTP 429 (or an already typed rate-limit)
     * becomes {@link LibreRateLimitedException} so callers can tell it from a generic error.
     */
    private static RuntimeException wrapFetchFailure(String prefix, Exception e) {
        String msg = prefix + e.getMessage();
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof LibreRateLimitedException
                    || (t instanceof HttpClientErrorException h && h.getStatusCode().value() == 429)) {
                return new LibreRateLimitedException(msg, e);
            }
        }
        return new RuntimeException(msg, e);
    }

    /**
     * Get glucose data for a patient. BE-1: per-user credentials. Failures (and an open circuit) are
     * thrown, never answered with empty data, so callers can tell "LibreLinkUp is down" from
     * "no new readings".
     */
    public LibreGlucoseData getGlucoseData(String patientId, int days, UUID userId) throws Exception {
        ensureAuthenticated(userId);
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-glucose-data:" + userId);

        try {
            return circuitBreaker.execute(() -> {
                try {
                    JsonNode jsonResponse = fetchGraph(userId, patientId);
                    throwIfApiError(jsonResponse, "LibreLinkUp glucose data error: ");

                    LibreGlucoseData glucoseData = responseParser.toGlucoseData(jsonResponse, patientId);
                    logger.debug("LibreLinkUp graph for patient {} returned {} readings",
                            patientId, glucoseData.getData().size());
                    return glucoseData;
                } catch (Exception e) {
                    logger.error("Failed to fetch LibreLinkUp glucose data for patient {}: {}", patientId, e.getMessage());
                    throw wrapFetchFailure("Failed to fetch LibreLinkUp glucose data: ", e);
                }
            });
        } catch (CircuitBreakerException e) {
            logger.warn("LibreLinkUp glucose data circuit breaker is OPEN");
            throw new RuntimeException("LibreLinkUp glucose data is temporarily unavailable after repeated failures.", e);
        }
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
        String key = cacheKey(userId, patientId);
        long now = sensorClock.millis();
        CachedSensor cached = sensorInfoCache.get(key);
        if (cached != null && now - cached.fetchedAtMs() < SENSOR_INFO_TTL.toMillis()) {
            logger.debug("Sensor info for patient {} served from the daily cache", patientId);
            return withCurrentAge(cached.info(), now);
        }

        ensureAuthenticated(userId);
        CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker("libre-sensor-info:" + userId);

        LibreSensorInfo info = cb.executeWithFallback(
            () -> {
                try {
                    JsonNode json = fetchGraph(userId, patientId);
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
        withCurrentAge(info, now);
        if (info.getSerialNumber() != null) {
            sensorInfoCache.put(key, new CachedSensor(info, now));
        }
        return info;
    }

    /**
     * The /graph document, downloaded once even when glucose sync and sensor info ask together.
     * A successful body also refreshes the daily sensor cache, so the sensor request does not
     * download the graph again.
     */
    private JsonNode fetchGraph(UUID userId, String patientId) throws Exception {
        String key = cacheKey(userId, patientId);
        CachedGraph cached = graphCache.get(key);
        if (cached != null) {
            long ageMs = sensorClock.millis() - cached.fetchedAtMs();
            if (ageMs < graphCacheTtlSeconds * 1000) {
                logger.debug("LibreLinkUp graph for patient {} served from cache ({} s old)", patientId, ageMs / 1000);
                return cached.body();
            }
            graphCache.remove(key, cached);
        }
        CompletableFuture<JsonNode> created = new CompletableFuture<>();
        CompletableFuture<JsonNode> existing = graphInflight.putIfAbsent(key, created);
        if (existing != null) {
            logger.info("Joining in-flight LibreLinkUp graph for patient {}", patientId);
            return awaitGraph(existing);
        }
        try {
            String url = baseUrlFor(userId) + "/llu/connections/" + patientId + "/graph";
            logger.info("Fetching LibreLinkUp graph for patient {} from {}", patientId, url);
            JsonNode json = authenticatedGet(userId, url);
            rememberSensor(userId, patientId, json);
            if (json != null && !json.has("error")) {
                graphCache.put(key, new CachedGraph(json, sensorClock.millis()));
            }
            created.complete(json);
            return json;
        } catch (Exception e) {
            created.completeExceptionally(e);
            throw e;
        } finally {
            graphInflight.remove(key, created);
        }
    }

    private static JsonNode awaitGraph(CompletableFuture<JsonNode> inflight) throws Exception {
        try {
            return inflight.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for LibreLinkUp graph", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    private void rememberSensor(UUID userId, String patientId, JsonNode json) {
        LibreSensorInfo info = responseParser.toSensorInfo(json, patientId);
        if (info.getSerialNumber() != null) {
            sensorInfoCache.put(cacheKey(userId, patientId), new CachedSensor(info, sensorClock.millis()));
        }
    }

    private static String cacheKey(UUID userId, String patientId) {
        return userId + "\0" + patientId;
    }

    /** Age and days left move on with the clock; the LibreLinkUp fetch does not. */
    private static LibreSensorInfo withCurrentAge(LibreSensorInfo info, long nowMs) {
        if (info.getActivationDate() == null || info.getSensorMaxDays() == null) {
            return info;
        }
        int ageDays = (int) ((nowMs - info.getActivationDate().getTime()) / 86_400_000L);
        int remaining = info.getSensorMaxDays() - ageDays;
        info.setSensorAgeDays(ageDays);
        info.setDaysRemaining(remaining);
        info.setStatus(ageDays < 0 ? "warmup" : remaining >= 0 ? "active" : "expired");
        return info;
    }

    /** True if the user has a stored LibreLinkUp token. BE-1: per-user. */
    public boolean isAuthenticated(UUID userId) {
        return sessionStore.isAuthenticated(userId);
    }

    /** Clear all LibreLinkUp session state for a user. BE-1: per-user. */
    public void logout(UUID userId) {
        sessionStore.clear(userId);
        if (userId == null) return;
        String prefix = userId + "\0";
        removeUserEntries(sensorInfoCache, prefix);
        removeUserEntries(graphCache, prefix);
    }

    private static void removeUserEntries(Map<String, ?> cache, String prefix) {
        Iterator<String> it = cache.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) it.remove();
        }
    }

    /**
     * Log in from the stored credentials when the in-memory token is gone. Sync and sensor info both
     * call this, and the per-user lock means a burst after a restart logs in once.
     */
    public void ensureSession(UUID userId) throws Exception {
        ensureAuthenticated(userId);
    }

    /**
     * Make sure the user has a LibreLinkUp session, logging in again from the stored credentials when
     * the in-memory token is gone (backend restart, or evicted after LibreLinkUp rejected it). Silent:
     * while working credentials are stored, callers never see "not authenticated". The per-user lock
     * makes a burst of requests after a restart log in once, not once each (LLU rate-limits logins).
     */
    private void ensureAuthenticated(UUID userId) throws Exception {
        if (sessionStore.token(userId) != null) {
            return;
        }
        synchronized (reloginLocks.computeIfAbsent(userId, k -> new Object())) {
            if (sessionStore.token(userId) != null) {
                return;
            }
            LibreAuthRequest stored = storedCredentials(userId);
            if (stored == null) {
                throw new RuntimeException("Not authenticated with LibreLinkUp");
            }
            logger.info("LibreLinkUp session missing for user {} - logging in again from stored credentials", userId);
            try {
                authenticate(stored, userId);
            } catch (LibreCredentialsRejectedException e) {
                // Same credentials will fail every time, and LLU rate-limits failed logins: stop
                // using them until the user saves new ones (UserDataSourceConfigService clears it).
                configRepository.markLibreCredentialsRejected(userId, Instant.now());
                logger.warn("LibreLinkUp rejected the stored credentials for user {} - sync paused until they are updated", userId);
                throw e;
            }
        }
    }

    /**
     * Authenticated GET with one silent re-login when LibreLinkUp rejects the token (expired/revoked):
     * drop the session, log in again from the stored credentials, retry once.
     */
    private JsonNode authenticatedGet(UUID userId, String url) throws Exception {
        try {
            return client.authenticatedGet(userId, url);
        } catch (HttpClientErrorException.Unauthorized e) {
            if (storedCredentials(userId) == null) {
                throw e;
            }
            logger.info("LibreLinkUp rejected the token for user {} - logging in again and retrying", userId);
            sessionStore.clear(userId);
            ensureAuthenticated(userId);
            return client.authenticatedGet(userId, url);
        }
    }

    /** The user's stored LibreLinkUp login, or null when none is configured. */
    private LibreAuthRequest storedCredentials(UUID userId) {
        if (userId == null) {
            return null;
        }
        return configRepository
                .findByUserIdAndDataSourceAndIsActiveTrue(userId, UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)
                .filter(c -> c.getLibreEmail() != null && c.getLibrePassword() != null)
                .filter(c -> c.getLibreCredentialsRejectedAt() == null)
                .map(c -> {
                    LibreAuthRequest request = new LibreAuthRequest();
                    request.setEmail(c.getLibreEmail());
                    request.setPassword(c.getLibrePassword());
                    request.setLocale(c.getLibreLocale());
                    return request;
                })
                .orElse(null);
    }

    /**
     * LibreLinkUp answers a wrong email/password with API status 2 ("incorrect username/password").
     * Throws {@link LibreCredentialsRejectedException} for that, so callers can stop retrying.
     */
    static void throwIfCredentialsRejected(JsonNode json) {
        if (json == null) {
            return;
        }
        boolean status2 = json.path("status").asInt(-1) == 2;
        String message = json.path("error").isObject() ? json.path("error").path("message").asText("")
                : json.path("error").asText("");
        if (status2 || message.toLowerCase().contains("incorrect username/password")) {
            throw new LibreCredentialsRejectedException("LibreLinkUp authentication error: "
                    + (message.isBlank() ? "incorrect username/password" : message));
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
