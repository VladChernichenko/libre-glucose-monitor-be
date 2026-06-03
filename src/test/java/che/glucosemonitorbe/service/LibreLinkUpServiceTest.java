package che.glucosemonitorbe.service;

import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.service.libre.LibreLinkUpClient;
import che.glucosemonitorbe.service.libre.LibreLinkUpRegionResolver;
import che.glucosemonitorbe.service.libre.LibreLinkUpResponseParser;
import che.glucosemonitorbe.service.libre.LibreLinkUpSessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for LibreLinkUpService after the BE-M5 decomposition.
 *
 * <p>Per-user session state now lives in {@link LibreLinkUpSessionStore} (injected), so tests set up
 * state via the store directly instead of reflecting on private fields. Wire/mapping helpers are
 * covered by {@link che.glucosemonitorbe.service.libre.LibreLinkUpResponseParserTest}.
 */
@ExtendWith(MockitoExtension.class)
class LibreLinkUpServiceTest {

    private LibreLinkUpSessionStore sessionStore;
    private LibreLinkUpResponseParser responseParser;
    private LibreLinkUpService service;

    @BeforeEach
    void setUp() {
        CircuitBreakerManager cbm = new CircuitBreakerManager();
        sessionStore = new LibreLinkUpSessionStore();
        responseParser = new LibreLinkUpResponseParser();
        LibreLinkUpClient client = new LibreLinkUpClient(new RestTemplate(), responseParser, sessionStore);
        service = new LibreLinkUpService(
                cbm,
                client,
                sessionStore,
                new LibreLinkUpRegionResolver(),
                responseParser);
    }

    // ── BE-1: per-user token isolation ────────────────────────────────────────

    @Test
    void be1_tokenStoreIsPerUser_user2TokenDoesNotOverwriteUser1() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();

        sessionStore.putToken(userId1, "token-A");
        sessionStore.putToken(userId2, "token-B");

        assertThat(sessionStore.token(userId1)).isEqualTo("token-A");
        assertThat(sessionStore.token(userId2)).isEqualTo("token-B");
    }

    @Test
    void be1_isAuthenticated_returnsTrueOnlyForUserWithToken() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();

        sessionStore.putToken(userId1, "token-A");

        assertThat(service.isAuthenticated(userId1)).isTrue();
        assertThat(service.isAuthenticated(userId2)).isFalse();
    }

    @Test
    void be1_logout_removesOnlyTargetUser() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();

        sessionStore.putToken(userId1, "token-A");
        sessionStore.putToken(userId2, "token-B");

        service.logout(userId1);

        assertThat(service.isAuthenticated(userId1)).isFalse();
        assertThat(service.isAuthenticated(userId2)).isTrue();
    }

    @Test
    void be1_isAuthenticated_nullUserIdReturnsFalse() {
        assertThat(service.isAuthenticated(null)).isFalse();
    }

    @Test
    void be1_logoutCycle_doesNotAffectOtherUsers() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();

        sessionStore.putToken(userId1, "token-A");
        sessionStore.putToken(userId2, "token-B");

        service.logout(userId1);
        assertThat(service.isAuthenticated(userId1)).isFalse();

        assertThat(service.isAuthenticated(userId2)).isTrue();
        assertThat(sessionStore.token(userId2)).isEqualTo("token-B");
    }

    @Test
    @DisplayName("logout — null userId is a no-op (no exception)")
    void logout_nullUserId_isNoop() {
        assertThat(service.isAuthenticated(null)).isFalse();
        service.logout(null); // must not throw
        assertThat(service.isAuthenticated(null)).isFalse();
    }

    @Test
    @DisplayName("logout — clears all per-user session state")
    void logout_clearsAllStores() {
        UUID uid = UUID.randomUUID();
        sessionStore.putToken(uid, "tok");
        sessionStore.putAccountId(uid, "acct-hash");
        sessionStore.putLocale(uid, "fr-FR");
        sessionStore.putBaseUrl(uid, "https://api-fr.libreview.io");

        service.logout(uid);

        assertThat(service.isAuthenticated(uid)).isFalse();
        assertThat(sessionStore.token(uid)).isNull();
        assertThat(sessionStore.accountId(uid)).isNull();
        // After clear the per-user overrides are gone, so the defaults are returned.
        assertThat(sessionStore.localeOrDefault(uid)).isEqualTo(LibreLinkUpSessionStore.DEFAULT_LOCALE);
        assertThat(sessionStore.baseUrlOrDefault(uid, "https://default")).isEqualTo("https://default");
    }

    // ── Auth-guard branches (public API) ──────────────────────────────────────

    @Test
    void be2_getConnections_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getConnections(userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    /**
     * BE-2: getConnections must unwrap the {"data":[...]} envelope. With transport now behind
     * LibreLinkUpClient this is finally testable end-to-end (the old reflection-only test could not
     * stub the HTTP call). A mocked client returns a canned envelope; the service must map it.
     */
    @Test
    void be2_getConnections_unwrapsDataEnvelopeAndMaps() throws Exception {
        UUID userId = UUID.randomUUID();
        sessionStore.putToken(userId, "tok");

        LibreLinkUpClient mockClient = mock(LibreLinkUpClient.class);
        JsonNode envelope = new ObjectMapper().readTree(
                "{\"data\":[{\"patientId\":\"p1\",\"firstName\":\"John\",\"lastName\":\"Doe\","
                        + "\"status\":\"active\",\"lastSync\":\"2025-01-01T00:00:00Z\"}]}");
        when(mockClient.authenticatedGet(eq(userId), anyString())).thenReturn(envelope);

        LibreLinkUpService svc = new LibreLinkUpService(
                new CircuitBreakerManager(), mockClient, sessionStore,
                new LibreLinkUpRegionResolver(), responseParser);

        List<LibreConnection> connections = svc.getConnections(userId);

        assertThat(connections).hasSize(1);
        assertThat(connections.get(0).getPatientId()).isEqualTo("p1");
        // The service stores "firstName lastName" in the connection's id field.
        assertThat(connections.get(0).getId()).isEqualTo("John Doe");
    }

    @Test
    @DisplayName("getSensorInfo — unauthenticated user throws RuntimeException")
    void getSensorInfo_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getSensorInfo("patient-1", userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    @DisplayName("getAlarms — unauthenticated user throws RuntimeException")
    void getAlarms_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getAlarms(userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    @DisplayName("getGlucoseHistory — unauthenticated user throws RuntimeException")
    void getGlucoseHistory_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getGlucoseHistory("patient-1", 7, null, null, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    @DisplayName("getCurrentGlucose — unauthenticated user throws RuntimeException")
    void getCurrentGlucose_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getCurrentGlucose("patient-1", userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    @DisplayName("getUserProfile — unauthenticated user throws RuntimeException")
    void getUserProfile_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getUserProfile(userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    @DisplayName("getGlucoseData — unauthenticated user throws RuntimeException")
    void getGlucoseData_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getGlucoseData("patient-1", 1, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    // ── Auth flow & data success paths (mocked transport client) ───────────────

    @Test
    @DisplayName("authenticate — stores token + account-id hash and returns the auth response")
    void authenticate_storesTokenAndAccountId() throws Exception {
        UUID userId = UUID.randomUUID();
        LibreAuthRequest req = new LibreAuthRequest("a@b.com", "pw");
        req.setLocale("en-GB");

        LibreLinkUpClient client = mock(LibreLinkUpClient.class);
        ResponseEntity<byte[]> resp = ResponseEntity.ok(new byte[0]);
        when(client.postLogin(anyString(), any(LibreAuthRequest.class))).thenReturn(resp);
        when(client.parse(any())).thenReturn(json(
                "{\"data\":{\"authTicket\":{\"token\":\"tok123\",\"expires\":111},\"user\":{\"id\":\"u1\"}}}"));

        LibreAuthResponse out = serviceWith(client).authenticate(req, userId);

        assertThat(out.getToken()).isEqualTo("tok123");
        assertThat(out.getExpires()).isEqualTo(111L);
        assertThat(sessionStore.token(userId)).isEqualTo("tok123");
        assertThat(sessionStore.accountId(userId)).isNotNull();   // SHA-256(user.id)
    }

    @Test
    @DisplayName("authenticate — region redirect follows the regional endpoint and pins its host")
    void authenticate_regionRedirect_followsRegionEndpoint() throws Exception {
        UUID userId = UUID.randomUUID();
        LibreAuthRequest req = new LibreAuthRequest("a@b.com", "pw");
        req.setLocale("en-GB");

        LibreLinkUpClient client = mock(LibreLinkUpClient.class);
        ResponseEntity<byte[]> r1 = new ResponseEntity<>(new byte[]{1}, HttpStatus.OK);
        ResponseEntity<byte[]> r2 = new ResponseEntity<>(new byte[]{2}, HttpStatus.OK);
        when(client.postLogin(anyString(), any(LibreAuthRequest.class))).thenReturn(r1, r2);
        when(client.parse(r1)).thenReturn(json("{\"data\":{\"redirect\":true,\"region\":\"us\"}}"));
        when(client.parse(r2)).thenReturn(json(
                "{\"data\":{\"authTicket\":{\"token\":\"tokUS\",\"expires\":222},\"user\":{\"id\":\"u2\"}}}"));

        LibreAuthResponse out = serviceWith(client).authenticate(req, userId);

        assertThat(out.getToken()).isEqualTo("tokUS");
        assertThat(sessionStore.baseUrlOrDefault(userId, "x")).isEqualTo("https://api-us.libreview.io");
    }

    @Test
    @DisplayName("getGlucoseData — maps the graph envelope (mg/dL → mmol/L)")
    void getGlucoseData_success_mapsGraphEnvelope() throws Exception {
        UUID userId = UUID.randomUUID();
        sessionStore.putToken(userId, "tok");
        LibreLinkUpClient client = mock(LibreLinkUpClient.class);
        when(client.authenticatedGet(eq(userId), anyString())).thenReturn(json(
                "{\"data\":{\"graphData\":[{\"ValueInMgPerDl\":180,"
                        + "\"FactoryTimestamp\":\"2025-01-14T22:22:33Z\",\"Trend\":3}]}}"));

        LibreGlucoseData data = serviceWith(client).getGlucoseData("p1", 1, userId);

        assertThat(data.getPatientId()).isEqualTo("p1");
        assertThat(data.getData()).hasSize(1);
        assertThat(data.getData().get(0).getValue()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("getCurrentGlucose — returns the most recent reading")
    void getCurrentGlucose_returnsLatest() throws Exception {
        UUID userId = UUID.randomUUID();
        sessionStore.putToken(userId, "tok");
        LibreLinkUpClient client = mock(LibreLinkUpClient.class);
        when(client.authenticatedGet(eq(userId), anyString())).thenReturn(json(
                "{\"data\":{\"graphData\":["
                        + "{\"ValueInMgPerDl\":90,\"FactoryTimestamp\":\"2025-01-14T22:00:00Z\",\"Trend\":4},"
                        + "{\"ValueInMgPerDl\":108,\"FactoryTimestamp\":\"2025-01-14T22:25:00Z\",\"Trend\":4}]}}"));

        LibreGlucoseReading r = serviceWith(client).getCurrentGlucose("p1", userId);

        assertThat(r.getValue()).isEqualTo(6.0);   // 108 / 18, the latest point
    }

    @Test
    @DisplayName("getSensorInfo — maps the active sensor (Libre 3, active)")
    void getSensorInfo_success_mapsActiveSensor() throws Exception {
        UUID userId = UUID.randomUUID();
        sessionStore.putToken(userId, "tok");
        long activation = System.currentTimeMillis() / 1000L - 3L * 86400L;
        LibreLinkUpClient client = mock(LibreLinkUpClient.class);
        when(client.authenticatedGet(eq(userId), anyString())).thenReturn(json(
                "{\"data\":{\"activeSensors\":[{\"sensor\":{\"sn\":\"S1\",\"a\":" + activation
                        + "},\"device\":{\"dtid\":40}}]}}"));

        LibreSensorInfo info = serviceWith(client).getSensorInfo("p1", userId);

        assertThat(info.getSensorModel()).isEqualTo("FreeStyle Libre 3");
        assertThat(info.getStatus()).isEqualTo("active");
        assertThat(info.getSerialNumber()).isEqualTo("S1");
    }

    @Test
    @DisplayName("getAlarms — maps thresholds (mg/dL → mmol/L)")
    void getAlarms_success_mapsThresholds() throws Exception {
        UUID userId = UUID.randomUUID();
        sessionStore.putToken(userId, "tok");
        LibreLinkUpClient client = mock(LibreLinkUpClient.class);
        when(client.authenticatedGet(eq(userId), anyString())).thenReturn(json(
                "{\"data\":{\"lowAlarm\":{\"enabled\":true,\"threshold\":70},"
                        + "\"highAlarm\":{\"enabled\":true,\"threshold\":240},"
                        + "\"signalLossAlarm\":{\"enabled\":false}}}"));

        LibreAlarms alarms = serviceWith(client).getAlarms(userId);

        assertThat(alarms.isLowAlarmEnabled()).isTrue();
        assertThat(alarms.getLowThresholdMmol()).isEqualTo(3.9);
        assertThat(alarms.isHighAlarmEnabled()).isTrue();
    }

    @Test
    @DisplayName("getUserProfile — returns the raw profile JSON")
    void getUserProfile_success_returnsJson() throws Exception {
        UUID userId = UUID.randomUUID();
        sessionStore.putToken(userId, "tok");
        LibreLinkUpClient client = mock(LibreLinkUpClient.class);
        when(client.authenticatedGet(eq(userId), anyString())).thenReturn(json("{\"firstName\":\"John\"}"));

        Object profile = serviceWith(client).getUserProfile(userId);

        assertThat(profile).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) profile).get("firstName").asText()).isEqualTo("John");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Build a service wired to a (usually mocked) transport client plus the real collaborators. */
    private LibreLinkUpService serviceWith(LibreLinkUpClient client) {
        return new LibreLinkUpService(new CircuitBreakerManager(), client, sessionStore,
                new LibreLinkUpRegionResolver(), responseParser);
    }

    private JsonNode json(String raw) throws Exception {
        return new ObjectMapper().readTree(raw);
    }
}
