package che.glucosemonitorbe.service;

import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.dto.LibreConnection;
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
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // -- BE-1: per-user token isolation ----------------------------------------

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
    @DisplayName("logout - null userId is a no-op (no exception)")
    void logout_nullUserId_isNoop() {
        assertThat(service.isAuthenticated(null)).isFalse();
        service.logout(null); // must not throw
        assertThat(service.isAuthenticated(null)).isFalse();
    }

    @Test
    @DisplayName("logout - clears all per-user session state")
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

    // -- Auth-guard branches (public API) --------------------------------------

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
    @DisplayName("getSensorInfo - unauthenticated user throws RuntimeException")
    void getSensorInfo_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getSensorInfo("patient-1", userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    @DisplayName("getGlucoseHistory - unauthenticated user throws RuntimeException")
    void getGlucoseHistory_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getGlucoseHistory("patient-1", 7, null, null, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    @DisplayName("getCurrentGlucose - unauthenticated user throws RuntimeException")
    void getCurrentGlucose_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getCurrentGlucose("patient-1", userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    @DisplayName("getGlucoseData - unauthenticated user throws RuntimeException")
    void getGlucoseData_requiresAuthentication() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getGlucoseData("patient-1", 1, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not authenticated");
    }
}
