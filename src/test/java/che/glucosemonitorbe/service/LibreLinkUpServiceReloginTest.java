package che.glucosemonitorbe.service;

import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.dto.LibreConnection;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.libre.LibreLinkUpClient;
import che.glucosemonitorbe.service.libre.LibreLinkUpRegionResolver;
import che.glucosemonitorbe.service.libre.LibreLinkUpResponseParser;
import che.glucosemonitorbe.service.libre.LibreLinkUpSessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Silent LibreLinkUp re-login in {@link LibreLinkUpService}: when the in-memory session is gone
 * (backend restart) or LibreLinkUp rejects the token, the service logs in again from the stored
 * credentials instead of failing with "Not authenticated with LibreLinkUp".
 */
class LibreLinkUpServiceReloginTest {

    private static final String CONNECTIONS = "{\"data\":[{\"patientId\":\"p1\",\"firstName\":\"John\","
            + "\"lastName\":\"Doe\",\"status\":\"active\",\"lastSync\":\"2025-01-01T00:00:00Z\"}]}";
    private static final String LOGIN_OK = "{\"status\":0,\"data\":{\"user\":{\"id\":\"llu-user\"},"
            + "\"authTicket\":{\"token\":\"fresh-token\",\"expires\":4102444800}}}";

    private final ObjectMapper json = new ObjectMapper();
    private LibreLinkUpSessionStore sessionStore;
    private LibreLinkUpClient client;
    private UserDataSourceConfigRepository configRepository;
    private LibreLinkUpService service;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        userId = UUID.randomUUID();
        sessionStore = new LibreLinkUpSessionStore();
        client = mock(LibreLinkUpClient.class);
        configRepository = mock(UserDataSourceConfigRepository.class);
        service = new LibreLinkUpService(new CircuitBreakerManager(), client, sessionStore,
                new LibreLinkUpRegionResolver(), new LibreLinkUpResponseParser(), configRepository);

        ResponseEntity<byte[]> loginResponse = ResponseEntity.ok(LOGIN_OK.getBytes(StandardCharsets.UTF_8));
        when(client.postLogin(anyString(), any())).thenReturn(loginResponse);
        when(client.parse(loginResponse)).thenReturn(json.readTree(LOGIN_OK));
    }

    @Test
    @DisplayName("no session after a restart: logs in from stored credentials, then answers")
    void missingSession_logsInAgainSilently() throws Exception {
        storeCredentials();
        when(client.authenticatedGet(eq(userId), anyString())).thenReturn(json.readTree(CONNECTIONS));

        List<LibreConnection> connections = service.getConnections(userId);

        assertThat(connections).hasSize(1);
        assertThat(sessionStore.token(userId)).isEqualTo("fresh-token");
        verify(client, times(1)).postLogin(anyString(), argThat(r -> "user@example.com".equals(r.getEmail())));
    }

    @Test
    @DisplayName("token rejected (401): logs in again and retries the call once")
    void rejectedToken_logsInAgainAndRetries() throws Exception {
        storeCredentials();
        sessionStore.putToken(userId, "expired-token");
        when(client.authenticatedGet(eq(userId), anyString()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8))
                .thenReturn(json.readTree(CONNECTIONS));

        List<LibreConnection> connections = service.getConnections(userId);

        assertThat(connections).hasSize(1);
        assertThat(sessionStore.token(userId)).isEqualTo("fresh-token");
        verify(client, times(2)).authenticatedGet(eq(userId), anyString());
    }

    @Test
    @DisplayName("a live session is reused - no extra login")
    void liveSession_noLogin() throws Exception {
        storeCredentials();
        sessionStore.putToken(userId, "live-token");
        when(client.authenticatedGet(eq(userId), anyString())).thenReturn(json.readTree(CONNECTIONS));

        service.getConnections(userId);

        verify(client, never()).postLogin(anyString(), any());
    }

    @Test
    @DisplayName("no stored credentials: still reports not authenticated")
    void noStoredCredentials_stillNotAuthenticated() {
        when(configRepository.findByUserIdAndDataSourceAndIsActiveTrue(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getGlucoseData("p1", 1, userId))
                .hasMessageContaining("Not authenticated");
    }

    private void storeCredentials() {
        UserDataSourceConfig c = new UserDataSourceConfig();
        c.setDataSource(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP);
        c.setLibreEmail("user@example.com");
        c.setLibrePassword("pw");
        c.setLibreLocale("en-GB");
        c.setIsActive(true);
        when(configRepository.findByUserIdAndDataSourceAndIsActiveTrue(
                eq(userId), eq(UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP))).thenReturn(Optional.of(c));
    }
}
