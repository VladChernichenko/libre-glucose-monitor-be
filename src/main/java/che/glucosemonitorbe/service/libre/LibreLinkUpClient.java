package che.glucosemonitorbe.service.libre;

import che.glucosemonitorbe.dto.LibreAuthRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * LibreLinkUp HTTP transport (BE-M5 decomposition). Owns the {@link RestTemplate}, builds the exact
 * headers LibreLinkUp requires, performs the login POST and authenticated GETs, and parses raw
 * (gzip/BOM-aware) byte responses to {@link JsonNode} via {@link LibreLinkUpResponseParser}.
 *
 * <p>Isolating transport here lets {@code LibreLinkUpService} (orchestration + mapping) be unit-tested
 * against a mocked client without a live network - previously impossible.
 */
@Component
public class LibreLinkUpClient {

    private static final Logger logger = LoggerFactory.getLogger(LibreLinkUpClient.class);

    /** LibreLinkUp Android client version sent as the {@code version} header. */
    private static final String CLIENT_VERSION = "4.16.0";

    private final RestTemplate restTemplate;
    private final LibreLinkUpResponseParser responseParser;
    private final LibreLinkUpSessionStore sessionStore;

    public LibreLinkUpClient(RestTemplate restTemplate,
                             LibreLinkUpResponseParser responseParser,
                             LibreLinkUpSessionStore sessionStore) {
        this.restTemplate = restTemplate;
        this.responseParser = responseParser;
        this.sessionStore = sessionStore;
    }

    /**
     * Canonical LibreLinkUp headers - verified against pylibrelinkup 0.10.0 and the khskekec dump.
     * cache-control and Connection casing are required exactly; deviations cause 400/430 on some edges.
     */
    private HttpHeaders loginHeaders(String locale) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");
        headers.set("accept-encoding", "gzip");           // lowercase; "br" triggers Brotli decode failure on JDK
        headers.set("cache-control", "no-cache");
        headers.set("connection", "keep-alive");
        headers.set("product", "llu.android");
        headers.set("version", CLIENT_VERSION);
        headers.set("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36");
        headers.set("Accept-Language", locale != null && !locale.isBlank() ? locale : "en-GB");
        return headers;
    }

    /** Headers for authenticated endpoints: Authorization + account-id + per-user Accept-Language. */
    private HttpHeaders authenticatedHeaders(UUID userId) {
        HttpHeaders headers = loginHeaders(sessionStore.localeOrDefault(userId));
        String token = sessionStore.token(userId);
        if (token != null) {
            headers.set("authorization", "Bearer " + token);
        }
        String accountId = sessionStore.accountId(userId);
        if (accountId != null) {
            headers.set("account-id", accountId);   // required: SHA-256(data.user.id from login response)
        } else {
            logger.warn("account-id not available for user {} - authenticated request may fail (RequiredHeaderMissing)", userId);
        }
        return headers;
    }

    /**
     * POST /llu/auth/login against {@code apiBaseUrl} (raw bytes). Throws {@link HttpClientErrorException}
     * on 4xx so the caller can try the next regional host.
     */
    public ResponseEntity<byte[]> postLogin(String apiBaseUrl, LibreAuthRequest authRequest) {
        String url = LibreLinkUpRegionResolver.normalizeBaseUrl(apiBaseUrl) + "/llu/auth/login";
        String locale = authRequest != null ? authRequest.getLocale() : null;
        // LibreLinkUp only accepts {"email","password"} - extra fields (locale, etc.) cause 400.
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", authRequest != null ? authRequest.getEmail() : "");
        body.put("password", authRequest != null ? authRequest.getPassword() : "");
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, loginHeaders(locale));
        logger.info("postLogin to {}: email={}", url, authRequest != null ? authRequest.getEmail() : "null");
        return restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);
    }

    /** GET {@code url} with authenticated headers for {@code userId}; returns the parsed body. */
    public JsonNode authenticatedGet(UUID userId, String url) throws Exception {
        HttpEntity<String> entity = new HttpEntity<>(authenticatedHeaders(userId));
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
        return responseParser.parseResponseBytes(response.getBody(), response.getHeaders());
    }

    /** Parse a raw response (used by the auth flow, which needs the multi-host retry around the POST). */
    public JsonNode parse(ResponseEntity<byte[]> response) throws Exception {
        return responseParser.parseResponseBytes(response.getBody(), response.getHeaders());
    }

    /** Translate a LibreLinkUp auth 4xx into a friendly RuntimeException (no multi-KB CDN HTML in the message). */
    public RuntimeException authError(HttpClientErrorException e) {
        int code = e.getStatusCode().value();
        String extra = "";
        if (code == 430 || code == 403) {
            extra = " Try setting libre.api.base-url to https://api-eu.libreview.io or https://api-us.libreview.io.";
        } else if (code == 429) {
            extra = " Wait several minutes before trying again; avoid repeated logins or \"Test connection\" in a short period.";
        }
        String detail = LibreLinkUpResponseParser.formatErrorBody(e.getResponseBodyAsByteArray());
        String msg = "LibreLinkUp authentication failed: HTTP " + code;
        if (!detail.isEmpty()) {
            msg += " - " + detail;
        }
        msg += extra;
        return new RuntimeException(msg, e);
    }
}
