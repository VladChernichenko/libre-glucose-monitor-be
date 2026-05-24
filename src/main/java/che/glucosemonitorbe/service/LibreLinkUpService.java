package che.glucosemonitorbe.service;

import che.glucosemonitorbe.circuitbreaker.CircuitBreaker;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerException;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Service
public class LibreLinkUpService {

    private static final Logger logger = LoggerFactory.getLogger(LibreLinkUpService.class);

    /**
     * When a host returns HTTP 403 or 430 (edge/WAF), try these after {@link #baseUrl}. Not used for 429 (rate limit).
     */
    private static final String[] LIBRE_AUTH_FALLBACK_BASES = {
            "https://api-eu.libreview.io",
            "https://api-fr.libreview.io",
            "https://api-us.libreview.io",
            "https://api-ap.libreview.io",
            "https://api-jp.libreview.io",
            "https://api-ae.libreview.io"
    };

    @Value("${libre.api.base-url:https://api-eu.libreview.io}")
    private String defaultBaseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerManager circuitBreakerManager;
    private final String clientVersion = "4.16.0";

    // BE-1 fix: per-user credential store — replaces shared authToken / baseUrl instance fields.
    // ConcurrentHashMap ensures thread-safe concurrent access without locking the whole service.
    private final ConcurrentHashMap<UUID, String> tokenStore       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> baseUrlStore     = new ConcurrentHashMap<>();
    // account-id fix: SHA-256(user.id) required on all authenticated LibreLinkUp requests (Nov 2024+).
    private final ConcurrentHashMap<UUID, String> accountIdStore   = new ConcurrentHashMap<>();
    // Locale fix: Accept-Language must match the account's locale (e.g. "fr-FR") or the API
    // returns MissingLocale / malformed responses for non-EN accounts.
    private final ConcurrentHashMap<UUID, String> localeStore      = new ConcurrentHashMap<>();

    /** Convenience: token for userId, or null if not authenticated. */
    private String tokenFor(UUID userId) {
        return userId != null ? tokenStore.get(userId) : null;
    }

    /** Resolved base URL for userId (falls back to configured default). */
    private String baseUrlFor(UUID userId) {
        if (userId != null) {
            String stored = baseUrlStore.get(userId);
            if (stored != null) return stored;
        }
        return normalizeLibreBaseUrl(defaultBaseUrl);
    }

    /** SHA-256(user.id) stored after login — required as account-id header (Nov 2024+). */
    private String accountIdFor(UUID userId) {
        return userId != null ? accountIdStore.get(userId) : null;
    }

    /** Accept-Language value for userId; falls back to "en-GB" (EU default). */
    private String localeFor(UUID userId) {
        if (userId != null) {
            String stored = localeStore.get(userId);
            if (stored != null && !stored.isBlank()) return stored;
        }
        return "en-GB";
    }

    /** Hex-encoded SHA-256 of the given string. Never throws — SHA-256 is always available on JVM. */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public LibreLinkUpService(CircuitBreakerManager circuitBreakerManager) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.circuitBreakerManager = circuitBreakerManager;
    }

    /**
     * Parse Libre auth response from raw bytes. Must use {@code byte[]} from RestTemplate, not {@code String},
     * or gzip payloads are corrupted by charset decoding (leading to 0x1F / JSON parse errors).
     */
    private JsonNode parseLibreAuthResponseBytes(byte[] body, HttpHeaders headers) throws Exception {
        if (body == null || body.length == 0) {
            throw new RuntimeException("Empty LibreLinkUp auth response body");
        }
        String contentEncoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
        boolean headerSaysGzip = contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip");
        boolean gzipMagic = body.length >= 2 && (body[0] & 0xFF) == 0x1f && (body[1] & 0xFF) == 0x8b;

        if (gzipMagic || headerSaysGzip) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return objectMapper.readTree(gis);
            } catch (IOException e) {
                logger.warn("Libre auth gzip decompress failed ({}), trying plain UTF-8 JSON", e.getMessage());
            }
        }

        int offset = 0;
        if (body.length >= 3 && (body[0] & 0xFF) == 0xef && (body[1] & 0xFF) == 0xbb && (body[2] & 0xFF) == 0xbf) {
            offset = 3;
        }
        return objectMapper.readTree(new String(body, offset, body.length - offset, StandardCharsets.UTF_8));
    }

    private static String normalizeLibreBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://api-eu.libreview.io";
        }
        return url.strip().replaceAll("/+$", "");
    }

    /**
     * Canonical LibreLinkUp headers — verified against pylibrelinkup 0.10.0 and the khskekec HTTP dump.
     * cache-control and Connection casing are required exactly as shown; deviations cause 400/430 on some CDN edges.
     * Accept-Language must match the account locale (e.g. "fr-FR") or the API returns MissingLocale errors.
     */
    private HttpHeaders buildLibreLoginHeaders(String locale) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");
        headers.set("accept-encoding", "gzip");           // lowercase; "br" triggers Brotli decode failure on JDK
        headers.set("cache-control", "no-cache");
        headers.set("connection", "keep-alive");
        headers.set("product", "llu.android");
        headers.set("version", clientVersion);
        headers.set("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36");
        headers.set("Accept-Language", locale != null && !locale.isBlank() ? locale : "en-GB");
        return headers;
    }

    /**
     * Build headers for authenticated endpoints (connections, graph, etc.).
     * Adds Authorization, account-id, and the per-user Accept-Language locale.
     */
    private HttpHeaders buildAuthenticatedHeaders(UUID userId) {
        HttpHeaders headers = buildLibreLoginHeaders(localeFor(userId));
        String token = tokenFor(userId);
        if (token != null) {
            headers.set("authorization", "Bearer " + token);
        }
        String accountId = accountIdFor(userId);
        if (accountId != null) {
            headers.set("account-id", accountId);   // required: SHA-256(data.user.id from login response)
        } else {
            logger.warn("account-id not available for user {} — authenticated request may fail (RequiredHeaderMissing)", userId);
        }
        return headers;
    }

    private ResponseEntity<byte[]> postLluAuthLogin(String apiBaseUrl, LibreAuthRequest authRequest) {
        String url = normalizeLibreBaseUrl(apiBaseUrl) + "/llu/auth/login";
        String locale = authRequest != null ? authRequest.getLocale() : null;
        // LibreLinkUp only accepts {"email","password"} — extra fields (locale, etc.) cause 400.
        java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("email", authRequest != null ? authRequest.getEmail() : "");
        body.put("password", authRequest != null ? authRequest.getPassword() : "");
        HttpEntity<java.util.Map<String, String>> entity = new HttpEntity<>(body, buildLibreLoginHeaders(locale));
        logger.info("postLluAuthLogin to {}: email={}", url, authRequest != null ? authRequest.getEmail() : "null");
        return restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);
    }

    /**
     * Only 403/430: try another host. Never retry on 429 — each host hit counts toward Cloudflare 1015.
     */
    private static boolean isRetryableAcrossLibreHosts(int httpStatus) {
        return httpStatus == 403 || httpStatus == 430;
    }

    /**
     * Short message for logs/API; avoids multi-KB Cloudflare HTML pages in exception text.
     */
    private static String formatLibreAuthErrorBody(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        String s = new String(raw, StandardCharsets.UTF_8).strip();
        if (s.isEmpty() || "{}".equals(s)) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (s.startsWith("<!DOCTYPE") || s.startsWith("<html") || lower.contains("cloudflare")) {
            if (lower.contains("1015") || lower.contains("rate limit") || lower.contains("being rate limited")) {
                return "Cloudflare rate limit (1015): too many requests from this IP; wait several minutes.";
            }
            return "CDN returned HTML (access blocked or challenge), not JSON.";
        }
        final int max = 400;
        if (s.length() > max) {
            return "Body: " + s.substring(0, max) + "...";
        }
        return "Body: " + s;
    }

    private RuntimeException libreAuthHttpError(HttpClientErrorException e) {
        int code = e.getStatusCode().value();
        String extra = "";
        if (code == 430 || code == 403) {
            extra = " Try setting libre.api.base-url to https://api-eu.libreview.io or https://api-us.libreview.io.";
        } else if (code == 429) {
            extra = " Wait several minutes before trying again; avoid repeated logins or \"Test connection\" in a short period.";
        }
        String detail = formatLibreAuthErrorBody(e.getResponseBodyAsByteArray());
        String msg = "LibreLinkUp authentication failed: HTTP " + code;
        if (!detail.isEmpty()) {
            msg += " — " + detail;
        }
        msg += extra;
        return new RuntimeException(msg, e);
    }

    /**
     * Maps an IETF locale tag to the most likely LibreLinkUp regional base URL.
     * Used to probe the locale-matched host first so accounts on regional endpoints
     * (e.g. "fr-FR" → api-fr.libreview.io) are not rejected with "wrong credentials"
     * by the wrong regional host.
     */
    private static String localeToBaseUrl(String locale) {
        if (locale == null || locale.isBlank()) return null;
        String lang = locale.toLowerCase(Locale.ROOT).split("[_\\-]")[0];
        switch (lang) {
            case "fr": return "https://api-fr.libreview.io";
            case "ja": return "https://api-jp.libreview.io";
            case "zh": return "https://api-ap.libreview.io";
            default:   return null;  // EU/US covered by defaultBaseUrl + fallback list
        }
    }

    /**
     * Authenticate with LibreLinkUp API and store the resulting token per-user.
     * BE-1 fix: credentials are keyed by userId — no more shared singleton state.
     */
    public LibreAuthResponse authenticate(LibreAuthRequest authRequest, UUID userId) throws Exception {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-auth");
        try {
            return circuitBreaker.execute(() -> {
                try {
                    LinkedHashSet<String> bases = new LinkedHashSet<>();
                    // Locale-first routing: probe the locale-matched host before the configured default.
                    // api-eu rejects French accounts with status:2 instead of a redirect, so "fr-FR"
                    // must hit api-fr.libreview.io directly.
                    String localeBase = localeToBaseUrl(authRequest.getLocale());
                    if (localeBase != null) {
                        bases.add(localeBase);
                    }
                    bases.add(normalizeLibreBaseUrl(defaultBaseUrl));
                    bases.addAll(Arrays.asList(LIBRE_AUTH_FALLBACK_BASES));
                    List<String> baseList = new ArrayList<>(bases);

                    String configuredBase = normalizeLibreBaseUrl(defaultBaseUrl);
                    ResponseEntity<byte[]> response = null;

                    for (int i = 0; i < baseList.size(); i++) {
                        String apiBase = baseList.get(i);
                        try {
                            logger.info("Authenticating with LibreLinkUp API at {}/llu/auth/login (password length={})",
                                    apiBase,
                                    authRequest.getPassword() != null ? authRequest.getPassword().length() : 0);
                            response = postLluAuthLogin(apiBase, authRequest);
                            break;
                        } catch (HttpClientErrorException e) {
                            int code = e.getStatusCode().value();
                            if (isRetryableAcrossLibreHosts(code) && i < baseList.size() - 1) {
                                logger.warn("LibreLinkUp auth HTTP {} from {}, trying next regional host", code, apiBase);
                                continue;
                            }
                            throw libreAuthHttpError(e);
                        }
                    }
                    if (response == null) {
                        throw new RuntimeException("LibreLinkUp authentication failed: no response from API hosts");
                    }

                    JsonNode jsonResponse = parseLibreAuthResponseBytes(response.getBody(), response.getHeaders());

                    logger.info("LibreLinkUp auth response: {}", jsonResponse.toString());

                    if (jsonResponse.has("error")) {
                        JsonNode errorNode = jsonResponse.get("error");
                        String errorMessage = errorNode.isObject()
                                ? errorNode.path("message").asText(errorNode.toString())
                                : errorNode.asText();
                        throw new RuntimeException("LibreLinkUp authentication error: " + errorMessage);
                    }

                    JsonNode data = jsonResponse.get("data");
                    if (data != null && data.has("redirect") && data.get("redirect").asBoolean()) {
                        String region = data.has("region") ? data.get("region").asText() : "";
                        logger.warn("LibreLinkUp requires region-specific endpoint. Region: {}", region);
                        if (!region.isEmpty()) {
                            return authenticateWithRegion(authRequest, region, userId);
                        }
                    }

                    JsonNode authTicket = jsonResponse.get("data") != null ?
                                        jsonResponse.get("data").get("authTicket") :
                                        jsonResponse.get("authTicket");
                    String token = null;
                    Long expires = null;

                    if (authTicket != null) {
                        token = authTicket.has("token") ? authTicket.get("token").asText() : null;
                        expires = authTicket.has("expires") ? authTicket.get("expires").asLong() :
                                 System.currentTimeMillis() + (24 * 60 * 60 * 1000);
                    }

                    if (token != null) {
                        if (userId != null) {
                            tokenStore.put(userId, token);
                            // Store the host that actually accepted auth (may differ from default)
                            // NOTE: currentBase captured from the loop — stored here after confirmed success
                            // Store locale for Accept-Language on subsequent requests
                            String locale = authRequest.getLocale();
                            if (locale != null && !locale.isBlank()) {
                                localeStore.put(userId, locale);
                                // Derive and store the base URL from the locale so subsequent calls use the right host
                                String lb = localeToBaseUrl(locale);
                                if (lb != null) {
                                    baseUrlStore.put(userId, lb);
                                    logger.info("Stored regional base URL {} for user {} (locale {})", lb, userId, locale);
                                }
                            }
                            // Extract user.id and store its SHA-256 hash as account-id (required since Nov 2024)
                            JsonNode userNode = data != null ? data.get("user") : null;
                            if (userNode != null && userNode.has("id")) {
                                String libreUserId = userNode.get("id").asText();
                                if (!libreUserId.isBlank()) {
                                    accountIdStore.put(userId, sha256Hex(libreUserId));
                                    logger.info("Stored account-id hash for user {}", userId);
                                }
                            }
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

    /**
     * Authenticate with region-specific endpoint. Stores credentials per userId.
     */
    private LibreAuthResponse authenticateWithRegion(LibreAuthRequest authRequest, String region, UUID userId) throws Exception {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-auth-region");
        try {
            return circuitBreaker.execute(() -> {
                try {
                    String regionBaseUrl = getRegionBaseUrl(region);
                    logger.info("Authenticating with region-specific LibreLinkUp API at {}/llu/auth/login", regionBaseUrl);
                    ResponseEntity<byte[]> response;
                    try {
                        response = postLluAuthLogin(regionBaseUrl, authRequest);
                    } catch (HttpClientErrorException e) {
                        throw libreAuthHttpError(e);
                    }

                    JsonNode jsonResponse = parseLibreAuthResponseBytes(response.getBody(), response.getHeaders());

                    logger.info("Region-specific auth response: {}", jsonResponse.toString());

                    JsonNode data = jsonResponse.get("data");
                    JsonNode authTicket = data != null ? data.get("authTicket") : null;
                    String token = null;
                    Long expires = null;

                    if (authTicket != null) {
                        token = authTicket.has("token") ? authTicket.get("token").asText() : null;
                        expires = authTicket.has("expires") ? authTicket.get("expires").asLong() :
                                 System.currentTimeMillis() + (24 * 60 * 60 * 1000);
                    }

                    if (token != null) {
                        if (userId != null) {
                            tokenStore.put(userId, token);
                            baseUrlStore.put(userId, regionBaseUrl);
                            // Store locale for Accept-Language on subsequent requests
                            String locale = authRequest.getLocale();
                            if (locale != null && !locale.isBlank()) {
                                localeStore.put(userId, locale);
                            }
                            // Extract user.id and store its SHA-256 hash as account-id (required since Nov 2024)
                            JsonNode userNode = data != null ? data.get("user") : null;
                            if (userNode != null && userNode.has("id")) {
                                String libreUserId = userNode.get("id").asText();
                                if (!libreUserId.isBlank()) {
                                    accountIdStore.put(userId, sha256Hex(libreUserId));
                                    logger.info("Stored account-id hash for user {} (region {})", userId, region);
                                }
                            }
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
     * Get region-specific base URL
     */
    private String getRegionBaseUrl(String region) {
        switch (region.toLowerCase()) {
            case "us":
            case "usa":
                return "https://api-us.libreview.io";
            case "fr":
                return "https://api-fr.libreview.io";
            case "eu":
            case "de":
            case "uk":
            case "gb":
                return "https://api-eu.libreview.io";
            case "ap":
            case "au":
            case "asia":
                return "https://api-ap.libreview.io";
            case "ae":
                return "https://api-ae.libreview.io";
            case "jp":
                return "https://api-jp.libreview.io";
            default:
                logger.warn("Unknown region {}, defaulting to EU", region);
                return "https://api-eu.libreview.io";
        }
    }

    /**
     * Get LibreLinkUp connections for a specific user.
     * BE-1 fix: uses per-user token/baseUrl.
     * BE-2 fix: unwraps the {"data":[...]} envelope before iterating.
     */
    public List<LibreConnection> getConnections(UUID userId) throws Exception {
        String authToken = tokenFor(userId);
        if (authToken == null) {
            throw new RuntimeException("Not authenticated with LibreLinkUp");
        }
        String baseUrl = baseUrlFor(userId);

        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-connections");

        return circuitBreaker.executeWithFallback(
            () -> {
                try {
                    String url = baseUrl + "/llu/connections";

                    HttpEntity<String> entity = new HttpEntity<>(buildAuthenticatedHeaders(userId));

                    logger.info("Fetching LibreLinkUp connections from {}", url);
                    ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);

                    if (response.getStatusCode() == HttpStatus.OK) {
                        JsonNode jsonResponse = parseLibreAuthResponseBytes(response.getBody(), response.getHeaders());
                        List<LibreConnection> connections = new ArrayList<>();

                        if (jsonResponse.has("error")) {
                            JsonNode e = jsonResponse.get("error");
                            throw new RuntimeException("LibreLinkUp connections error: " + (e.isObject() ? e.path("message").asText(e.toString()) : e.asText()));
                        }

                        // BE-2 fix: API returns {"data":[...]} — unwrap the envelope
                        JsonNode dataNode = jsonResponse.get("data");
                        if (dataNode != null && dataNode.isArray()) {
                            for (JsonNode connection : dataNode) {
                                String patientId = connection.has("patientId") ? connection.get("patientId").asText() : "";
                                String firstName = connection.has("firstName") ? connection.get("firstName").asText() : "";
                                String lastName  = connection.has("lastName")  ? connection.get("lastName").asText()  : "";
                                String status    = connection.has("status")    ? connection.get("status").asText()    : "active";
                                String lastSync  = connection.has("lastSync")  ? connection.get("lastSync").asText()  : Instant.now().toString();

                                connections.add(new LibreConnection(
                                    firstName + " " + lastName,
                                    patientId,
                                    status,
                                    lastSync
                                ));
                            }
                        }

                        logger.info("Successfully fetched {} LibreLinkUp connections for user {}", connections.size(), userId);
                        return connections;
                    } else {
                        throw new RuntimeException("Failed to fetch connections with status: " + response.getStatusCode());
                    }
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

    /**
     * Get glucose data for a specific patient. BE-1 fix: uses per-user credentials.
     */
    public LibreGlucoseData getGlucoseData(String patientId, int days, UUID userId) throws Exception {
        String authToken = tokenFor(userId);
        if (authToken == null) {
            throw new RuntimeException("Not authenticated with LibreLinkUp");
        }
        String baseUrl = baseUrlFor(userId);

        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-glucose-data");
        
        return circuitBreaker.executeWithFallback(
            () -> {
                try {
                    String url = baseUrl + "/llu/connections/" + patientId + "/graph";

                    HttpEntity<String> entity = new HttpEntity<>(buildAuthenticatedHeaders(userId));

                    logger.info("Fetching LibreLinkUp glucose data for patient {} from {}", patientId, url);
                    ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);

                    if (response.getStatusCode() == HttpStatus.OK) {
                        JsonNode jsonResponse = parseLibreAuthResponseBytes(response.getBody(), response.getHeaders());
                        List<LibreGlucoseReading> readings = new ArrayList<>();
                        
                        // Check for error in response
                        if (jsonResponse.has("error")) {
                            JsonNode e = jsonResponse.get("error");
                            throw new RuntimeException("LibreLinkUp glucose data error: " + (e.isObject() ? e.path("message").asText(e.toString()) : e.asText()));
                        }
                        
                        // LLU graph response envelope: {"status":0,"data":{"graphData":[...],...}}
                        JsonNode dataEnvelope = jsonResponse.get("data");
                        JsonNode graphData = dataEnvelope != null ? dataEnvelope.get("graphData") : jsonResponse.get("graphData");
                        logger.info("LLU graph response structure: dataEnvelope={}, graphData size={}",
                                dataEnvelope != null ? "present" : "absent",
                                graphData != null && graphData.isArray() ? graphData.size() : "null/not-array");
                        if (graphData != null && graphData.isArray()) {
                            for (JsonNode point : graphData) {
                                // ValueInMgPerDl is always mg/dL regardless of account unit setting.
                                // "Value" is in the account's display unit (mmol/L or mg/dL) — don't use it.
                                double valueMgDl = 0;
                                if (point.has("ValueInMgPerDl")) {
                                    valueMgDl = point.get("ValueInMgPerDl").asDouble();
                                } else if (point.has("value")) {
                                    valueMgDl = point.get("value").asDouble();
                                } else if (point.has("glucoseValue")) {
                                    valueMgDl = point.get("glucoseValue").asDouble();
                                }

                                // Convert from mg/dL to mmol/L
                                double valueMmolL = valueMgDl / 18.0;

                                // FactoryTimestamp is UTC; Timestamp is local. Prefer FactoryTimestamp.
                                String timestamp = "";
                                if (point.has("FactoryTimestamp")) {
                                    timestamp = point.get("FactoryTimestamp").asText();
                                } else if (point.has("Timestamp")) {
                                    timestamp = point.get("Timestamp").asText();
                                } else if (point.has("timestamp")) {
                                    timestamp = point.get("timestamp").asText();
                                }

                                int trend = point.has("Trend") ? point.get("Trend").asInt() : 0;
                                String trendArrow = convertTrendToArrow(trend);

                                // Only add valid readings
                                if (valueMgDl > 0 && !timestamp.isEmpty()) {
                                    readings.add(new LibreGlucoseReading(
                                        parseTimestamp(timestamp),
                                        valueMmolL,
                                        trend,
                                        trendArrow,
                                        getGlucoseStatus(valueMmolL),
                                        "mmol/L",
                                        parseTimestamp(timestamp)
                                    ));
                                }
                            }
                        }

                        // Always check connection.glucoseMeasurement — it is the live reading and can be
                        // more recent than the last graphData entry. Add it if newer or if graphData empty.
                        if (dataEnvelope != null) {
                            JsonNode conn = dataEnvelope.get("connection");
                            JsonNode gm = conn != null ? conn.get("glucoseMeasurement") : null;
                            if (gm != null) {
                                // ValueInMgPerDl is always mg/dL; Value may be mmol/L depending on account unit.
                                double valueMgDl = gm.has("ValueInMgPerDl") ? gm.get("ValueInMgPerDl").asDouble()
                                                 : gm.has("Value")          ? gm.get("Value").asDouble() : 0;
                                String ts = gm.has("FactoryTimestamp") ? gm.get("FactoryTimestamp").asText()
                                          : gm.has("Timestamp")        ? gm.get("Timestamp").asText() : "";
                                int trend = gm.has("Trend") ? gm.get("Trend").asInt() : 0;
                                if (valueMgDl > 0 && !ts.isEmpty()) {
                                    Date gmDate = parseTimestamp(ts);
                                    boolean isNewer = readings.isEmpty()
                                            || gmDate.after(readings.get(readings.size() - 1).getTimestamp());
                                    if (isNewer) {
                                        double mmol = valueMgDl / 18.0;
                                        readings.add(new LibreGlucoseReading(
                                            gmDate, mmol, trend, convertTrendToArrow(trend),
                                            getGlucoseStatus(mmol), "mmol/L", gmDate
                                        ));
                                        logger.info("Added glucoseMeasurement ({} mg/dL, trend={}) as latest reading for patient {}",
                                            valueMgDl, trend, patientId);
                                    }
                                }
                            }
                        }

                        logger.info("Successfully fetched {} glucose readings for patient {}", readings.size(), patientId);
                        return new LibreGlucoseData(
                            patientId,
                            readings,
                            Instant.now().minusSeconds(12 * 60 * 60).toString(), // Last 12 hours
                            Instant.now().toString(),
                            "mmol/L"
                        );
                    } else {
                        throw new RuntimeException("Failed to fetch glucose data with status: " + response.getStatusCode());
                    }
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

    /**
     * Get current glucose reading for a specific patient. BE-1 fix: uses per-user credentials.
     */
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
     * Get user profile information. BE-1 fix: uses per-user credentials.
     */
    public Object getUserProfile(UUID userId) throws Exception {
        String authToken = tokenFor(userId);
        if (authToken == null) {
            throw new RuntimeException("Not authenticated with LibreLinkUp");
        }
        String baseUrl = baseUrlFor(userId);

        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("libre-profile");

        return circuitBreaker.executeWithFallback(
            () -> {
                try {
                    String url = baseUrl + "/user/profile";

                    HttpEntity<String> entity = new HttpEntity<>(buildAuthenticatedHeaders(userId));
                    
                    logger.info("Fetching LibreLinkUp user profile from {}", url);
                    ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);

                    if (response.getStatusCode() == HttpStatus.OK) {
                        JsonNode jsonResponse = parseLibreAuthResponseBytes(response.getBody(), response.getHeaders());
                        
                        // Check for error in response
                        if (jsonResponse.has("error")) {
                            JsonNode e = jsonResponse.get("error");
                            throw new RuntimeException("LibreLinkUp user profile error: " + (e.isObject() ? e.path("message").asText(e.toString()) : e.asText()));
                        }
                        
                        logger.info("Successfully fetched LibreLinkUp user profile");
                        return jsonResponse;
                    } else {
                        throw new RuntimeException("Failed to fetch user profile with status: " + response.getStatusCode());
                    }
                } catch (Exception e) {
                    logger.error("Failed to fetch LibreLinkUp user profile: {}", e.getMessage());
                    throw new RuntimeException("Failed to fetch LibreLinkUp user profile: " + e.getMessage());
                }
            },
            () -> {
                logger.warn("LibreLinkUp user profile circuit breaker is OPEN - returning empty profile");
                return objectMapper.createObjectNode();
            }
        );
    }

    /**
     * Parse timestamp string to Date object.
     * Handles ISO-8601, Unix ms, and LibreLinkUp regional formats:
     *   US: "M/d/yyyy h:mm:ss a"  (e.g. "1/14/2026 10:22:33 PM")
     *   EU: "dd/MM/yyyy HH:mm:ss" (e.g. "14/01/2026 22:22:33")
     */
    private Date parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return new Date();
        // ISO-8601
        if (timestamp.contains("T")) {
            try { return new Date(Instant.parse(timestamp).toEpochMilli()); } catch (Exception ignored) {}
        }
        // Unix milliseconds
        try { return new Date(Long.parseLong(timestamp.trim())); } catch (Exception ignored) {}
        // Regional date formats — FactoryTimestamp is always UTC, so parse as UTC explicitly.
        // Using ZoneId.systemDefault() on a non-UTC server would shift all timestamps wrongly.
        for (String pattern : new String[]{"M/d/yyyy h:mm:ss a", "M/d/yyyy H:mm:ss", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm"}) {
            try {
                return java.util.Date.from(java.time.LocalDateTime.parse(
                        timestamp.trim(),
                        java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US))
                    .atZone(java.time.ZoneOffset.UTC).toInstant());
            } catch (Exception ignored) {}
        }
        logger.warn("Failed to parse timestamp: {}, using current time", timestamp);
        return new Date();
    }

    /**
     * Convert LibreLinkUp trend number to arrow symbol.
     * LLU uses a 1–7 scale (same as Nightscout direction names):
     *   1 = DoubleUp   ↑↑
     *   2 = SingleUp   ↑
     *   3 = FortyFiveUp ↗
     *   4 = Flat        →
     *   5 = FortyFiveDown ↘
     *   6 = SingleDown  ↓
     *   7 = DoubleDown  ↓↓
     */
    private String convertTrendToArrow(int trend) {
        switch (trend) {
            case 1: return "↑↑"; // ↑↑ DoubleUp
            case 2: return "↑";       // ↑  SingleUp
            case 3: return "↗";       // ↗  FortyFiveUp
            case 4: return "→";       // →  Flat
            case 5: return "↘";       // ↘  FortyFiveDown
            case 6: return "↓";       // ↓  SingleDown
            case 7: return "↓↓"; // ↓↓ DoubleDown
            default: return "→";      // → unknown
        }
    }

    /**
     * Determine glucose status based on value in mmol/L
     */
    private String getGlucoseStatus(double value) {
        if (value < 3.9) return "low";      // < 70 mg/dL
        if (value < 10.0) return "normal";  // 70-180 mg/dL
        if (value < 13.9) return "high";    // 180-250 mg/dL
        return "critical";                   // > 250 mg/dL
    }

    /**
     * Get historical glucose data for a specific patient.
     * Delegates to {@link #getGlucoseData} (both call the same LLU /graph endpoint)
     * and applies optional date filtering on the result.
     */
    public LibreGlucoseData getGlucoseHistory(String patientId, int days, String startDate, String endDate, UUID userId) throws Exception {
        LibreGlucoseData data = getGlucoseData(patientId, days, userId);
        // Apply optional client-side date window if provided.
        if (startDate != null || endDate != null) {
            java.time.Instant start = startDate != null ? parseTimestamp(startDate).toInstant() : Instant.EPOCH;
            java.time.Instant end   = endDate   != null ? parseTimestamp(endDate).toInstant()   : Instant.now();
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
        if (tokenFor(userId) == null) {
            throw new RuntimeException("Not authenticated with LibreLinkUp");
        }
        String baseUrl = baseUrlFor(userId);
        CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker("libre-sensor-info");

        return cb.executeWithFallback(
            () -> {
                try {
                    String url = baseUrl + "/llu/connections/" + patientId + "/graph";
                    HttpEntity<String> entity = new HttpEntity<>(buildAuthenticatedHeaders(userId));
                    logger.info("Fetching sensor info for patient {} from {}", patientId, url);
                    ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
                    JsonNode json = parseLibreAuthResponseBytes(response.getBody(), response.getHeaders());

                    JsonNode data = json.get("data");
                    JsonNode activeSensors = data != null ? data.get("activeSensors") : null;
                    if (activeSensors == null || !activeSensors.isArray() || activeSensors.isEmpty()) {
                        logger.warn("No activeSensors in graph response for patient {}", patientId);
                        return buildUnknownSensorInfo();
                    }

                    JsonNode sensorEntry = activeSensors.get(0);
                    JsonNode sensor = sensorEntry.get("sensor");
                    JsonNode device = sensorEntry.get("device");

                    // Serial number
                    String sn = sensor != null && sensor.has("sn") ? sensor.get("sn").asText() : null;

                    // Activation timestamp (Unix seconds)
                    long activationEpoch = sensor != null && sensor.has("a") ? sensor.get("a").asLong(0) : 0;
                    Date activationDate = activationEpoch > 0
                            ? new Date(activationEpoch * 1000L) : null;

                    // Sensor max lifetime — LLU 'w' field is warm-up minutes; pt=0→Libre3(14d), else 14d default
                    int sensorMaxDays = 14;
                    Date expiryDate = activationDate != null
                            ? new Date(activationDate.getTime() + (long) sensorMaxDays * 86400 * 1000L)
                            : null;

                    // Age and remaining
                    Integer ageDays = null;
                    Integer remaining = null;
                    if (activationDate != null) {
                        long ageMs = System.currentTimeMillis() - activationDate.getTime();
                        ageDays  = (int) (ageMs / (86400 * 1000L));
                        remaining = sensorMaxDays - ageDays;
                    }

                    // Status
                    String status = "unknown";
                    if (ageDays != null) {
                        if (ageDays < 0) status = "warmup";
                        else if (remaining != null && remaining >= 0) status = "active";
                        else status = "expired";
                    }

                    // Sensor model from device type id (dtid)
                    String model = "FreeStyle Libre";
                    if (device != null && device.has("dtid")) {
                        int dtid = device.get("dtid").asInt();
                        if (dtid == 40 || dtid == 41) model = "FreeStyle Libre 3";
                        else if (dtid == 30 || dtid == 31) model = "FreeStyle Libre 2";
                        else if (dtid == 5) model = "FreeStyle Libre";
                    }

                    logger.info("Sensor info for patient {}: model={}, sn={}, age={}d, remaining={}d, status={}",
                            patientId, model, sn, ageDays, remaining, status);
                    return new LibreSensorInfo(sn, model, activationDate, expiryDate,
                            ageDays, sensorMaxDays, status, remaining);
                } catch (Exception e) {
                    logger.error("Failed to fetch sensor info for patient {}: {}", patientId, e.getMessage());
                    throw new RuntimeException("Failed to fetch sensor info: " + e.getMessage(), e);
                }
            },
            () -> {
                logger.warn("LibreLinkUp sensor info circuit breaker is OPEN");
                return buildUnknownSensorInfo();
            }
        );
    }

    private LibreSensorInfo buildUnknownSensorInfo() {
        return new LibreSensorInfo(null, "FreeStyle Libre", null, null, null, 14, "unknown", null);
    }

    /**
     * Fetch glucose alarm configuration from LibreLinkUp {@code /llu/notifications/alarms}.
     */
    public LibreAlarms getAlarms(UUID userId) throws Exception {
        if (tokenFor(userId) == null) {
            throw new RuntimeException("Not authenticated with LibreLinkUp");
        }
        String baseUrl = baseUrlFor(userId);
        CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker("libre-alarms");

        return cb.executeWithFallback(
            () -> {
                try {
                    String url = baseUrl + "/llu/notifications/alarms";
                    HttpEntity<String> entity = new HttpEntity<>(buildAuthenticatedHeaders(userId));
                    logger.info("Fetching LLU alarms from {}", url);
                    ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
                    JsonNode json = parseLibreAuthResponseBytes(response.getBody(), response.getHeaders());

                    if (json.has("error")) {
                        JsonNode e = json.get("error");
                        throw new RuntimeException("LLU alarms error: " +
                                (e.isObject() ? e.path("message").asText(e.toString()) : e.asText()));
                    }

                    JsonNode data = json.has("data") ? json.get("data") : json;
                    JsonNode low  = data.get("lowAlarm");
                    JsonNode high = data.get("highAlarm");
                    JsonNode sig  = data.get("signalLossAlarm");

                    boolean lowEnabled  = low  != null && low.path("enabled").asBoolean(false);
                    boolean highEnabled = high != null && high.path("enabled").asBoolean(false);
                    boolean sigEnabled  = sig  != null && sig.path("enabled").asBoolean(false);

                    Double lowMgDl  = low  != null && low.has("threshold")  ? low.get("threshold").asDouble()  : null;
                    Double highMgDl = high != null && high.has("threshold") ? high.get("threshold").asDouble() : null;
                    Integer lowSnooze  = low  != null && low.has("snooze")  ? low.get("snooze").asInt()  : null;
                    Integer highSnooze = high != null && high.has("snooze") ? high.get("snooze").asInt() : null;

                    Double lowMmol  = lowMgDl  != null ? Math.round(lowMgDl  / 18.0 * 10.0) / 10.0 : null;
                    Double highMmol = highMgDl != null ? Math.round(highMgDl / 18.0 * 10.0) / 10.0 : null;

                    logger.info("LLU alarms for user {}: low={} @{}mmol, high={} @{}mmol, signalLoss={}",
                            userId, lowEnabled, lowMmol, highEnabled, highMmol, sigEnabled);
                    return new LibreAlarms(lowEnabled, lowMgDl, lowMmol, lowSnooze,
                            highEnabled, highMgDl, highMmol, highSnooze, sigEnabled);
                } catch (Exception e) {
                    logger.error("Failed to fetch LLU alarms for user {}: {}", userId, e.getMessage());
                    throw new RuntimeException("Failed to fetch LLU alarms: " + e.getMessage(), e);
                }
            },
            () -> {
                logger.warn("LibreLinkUp alarms circuit breaker is OPEN - returning disabled alarms");
                return new LibreAlarms(false, null, null, null, false, null, null, null, false);
            }
        );
    }

    /**
     * Check if a specific user is authenticated with LibreLinkUp. BE-1 fix: per-user.
     */
    public boolean isAuthenticated(UUID userId) {
        return userId != null && tokenStore.containsKey(userId);
    }

    /**
     * Clear authentication token for a specific user. BE-1 fix: per-user.
     */
    public void logout(UUID userId) {
        if (userId != null) {
            tokenStore.remove(userId);
            baseUrlStore.remove(userId);
            accountIdStore.remove(userId);
            localeStore.remove(userId);
        }
    }
}
