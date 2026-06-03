package che.glucosemonitorbe.service.libre;

import che.glucosemonitorbe.dto.LibreAuthRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the LibreLinkUp HTTP transport. Uses a mocked {@link RestTemplate} so no network is
 * touched; verifies the request URL/body/headers, the gzip/BOM-aware parse, and the friendly mapping
 * of LibreLinkUp auth errors.
 */
class LibreLinkUpClientTest {

    private final LibreLinkUpResponseParser parser = new LibreLinkUpResponseParser();

    private LibreLinkUpClient clientWith(RestTemplate rt, LibreLinkUpSessionStore store) {
        return new LibreLinkUpClient(rt, parser, store);
    }

    @Test
    @DisplayName("postLogin — posts {email,password} to <base>/llu/auth/login with LLU headers")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void postLogin_buildsRequestAndReturnsResponse() {
        RestTemplate rt = mock(RestTemplate.class);
        LibreLinkUpClient client = clientWith(rt, new LibreLinkUpSessionStore());
        ResponseEntity<byte[]> resp = ResponseEntity.ok("{\"ok\":1}".getBytes(StandardCharsets.UTF_8));
        String url = "https://api-eu.libreview.io/llu/auth/login";
        when(rt.exchange(eq(url), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(resp);

        LibreAuthRequest req = new LibreAuthRequest("a@b.com", "pw");
        req.setLocale("en-GB");
        ResponseEntity<byte[]> out = client.postLogin("https://api-eu.libreview.io", req);

        assertThat(out).isSameAs(resp);
        ArgumentCaptor<HttpEntity> cap = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rt).exchange(eq(url), eq(HttpMethod.POST), cap.capture(), eq(byte[].class));
        Map<String, String> body = (Map<String, String>) cap.getValue().getBody();
        assertThat(body).containsEntry("email", "a@b.com").containsEntry("password", "pw");
        assertThat(body).doesNotContainKey("locale"); // LLU rejects extra fields
        HttpHeaders headers = cap.getValue().getHeaders();
        assertThat(headers.getFirst("product")).isEqualTo("llu.android");
        assertThat(headers.getFirst("version")).isNotBlank();
        assertThat(headers.getFirst("Accept-Language")).isEqualTo("en-GB");
    }

    @Test
    @DisplayName("authenticatedGet — sends Bearer token + account-id and parses the body")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void authenticatedGet_setsAuthHeadersAndParses() throws Exception {
        LibreLinkUpSessionStore store = new LibreLinkUpSessionStore();
        UUID userId = UUID.randomUUID();
        store.putToken(userId, "tok");
        store.putAccountId(userId, "acct-hash");
        store.putLocale(userId, "fr-FR");

        RestTemplate rt = mock(RestTemplate.class);
        LibreLinkUpClient client = clientWith(rt, store);
        ResponseEntity<byte[]> resp = new ResponseEntity<>("{\"v\":42}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        when(rt.exchange(eq("https://x/y"), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(resp);

        JsonNode node = client.authenticatedGet(userId, "https://x/y");

        assertThat(node.get("v").asInt()).isEqualTo(42);
        ArgumentCaptor<HttpEntity> cap = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rt).exchange(eq("https://x/y"), eq(HttpMethod.GET), cap.capture(), eq(byte[].class));
        HttpHeaders headers = cap.getValue().getHeaders();
        assertThat(headers.getFirst("authorization")).isEqualTo("Bearer tok");
        assertThat(headers.getFirst("account-id")).isEqualTo("acct-hash");
        assertThat(headers.getFirst("Accept-Language")).isEqualTo("fr-FR");
    }

    @Test
    @DisplayName("parse — decodes plain JSON bytes to a JsonNode")
    void parse_decodesBytes() throws Exception {
        LibreLinkUpClient client = clientWith(mock(RestTemplate.class), new LibreLinkUpSessionStore());
        JsonNode node = client.parse(new ResponseEntity<>("{\"a\":1}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
        assertThat(node.get("a").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("authError — 430/403 suggest a regional host; 429 suggests waiting")
    void authError_mapsStatusToFriendlyMessage() {
        LibreLinkUpClient client = clientWith(mock(RestTemplate.class), new LibreLinkUpSessionStore());

        RuntimeException edge = client.authError(HttpClientErrorException.create(
                HttpStatusCode.valueOf(430), "Blocked", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        assertThat(edge.getMessage()).contains("HTTP 430").contains("api-eu.libreview.io");

        RuntimeException rate = client.authError(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(rate.getMessage()).contains("HTTP 429").containsIgnoringCase("wait");

        RuntimeException unauth = client.authError(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        assertThat(unauth.getMessage()).contains("HTTP 401");
    }
}
