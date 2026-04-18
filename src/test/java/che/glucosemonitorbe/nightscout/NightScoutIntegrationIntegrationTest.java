package che.glucosemonitorbe.nightscout;

import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.dto.NightscoutCredentials;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.dto.NightscoutTestResponseDto;
import che.glucosemonitorbe.service.UserDataSourceConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class NightScoutIntegrationIntegrationTest {

    @Mock
    private UserDataSourceConfigService userDataSourceConfigService;

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private NightScoutIntegration integration;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        integration = new NightScoutIntegration(
                restTemplate,
                new ObjectMapper(),
                userDataSourceConfigService,
                new CircuitBreakerManager()
        );
    }

    @Test
    void getGlucoseEntriesShouldParseNightscoutPayload() throws Exception {
        UUID userId = UUID.randomUUID();
        NightscoutCredentials creds = new NightscoutCredentials(
                "https://nightscout.example.com", "my-secret", "my-token");
        when(userDataSourceConfigService.getNightscoutCredentials(userId))
                .thenReturn(Optional.of(creds));

        server.expect(once(), requestTo("https://nightscout.example.com/api/v2/entries.json?count=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer my-token"))
                .andExpect(header("api-secret", sha1("my-secret")))
                .andRespond(withSuccess("[{\"_id\":\"1\",\"sgv\":123,\"date\":1711990000}]", MediaType.APPLICATION_JSON));

        List<NightscoutEntryDto> entries = integration.getGlucoseEntries(userId, 1);

        assertEquals(1, entries.size());
        assertEquals(123, entries.get(0).getSgv());
        server.verify();
    }

    @Test
    void probeNightscoutShouldRejectInvalidUrl() {
        NightscoutTestResponseDto result = integration.probeNightscout("nightscout.local", "", "");

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("http:// or https://"));
    }

    private static String sha1(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            String part = Integer.toHexString(0xff & b);
            if (part.length() == 1) {
                hex.append('0');
            }
            hex.append(part);
        }
        return hex.toString();
    }
}
