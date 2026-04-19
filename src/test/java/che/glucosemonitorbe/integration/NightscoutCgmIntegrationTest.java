package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.DataSourceConfigRequestDto;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.dto.NightscoutTestRequestDto;
import che.glucosemonitorbe.dto.NightscoutTestResponseDto;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.dto.UserDataSourceConfigDto;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class NightscoutCgmIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    private RegisterRequest validRegister() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest r = new RegisterRequest();
        r.setUsername("nsuser_" + suffix);
        r.setEmail("ns+" + suffix + "@example.com");
        r.setFullName("NS User");
        r.setPassword("testpass123");
        return r;
    }

    private HttpHeaders authedHeaders(RegisterRequest register) {
        rest.postForEntity("/api/auth/register", jsonEntity(register), String.class);

        AuthRequest login = new AuthRequest();
        login.setUsername(register.getUsername());
        login.setPassword(register.getPassword());
        ResponseEntity<AuthResponse> loginResp =
                rest.postForEntity("/api/auth/login", jsonEntity(login), AuthResponse.class);
        assertNotNull(loginResp.getBody());
        String token = loginResp.getBody().getAccessToken();

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    @Test
    @DisplayName("Save UserDataSourceConfig for Nightscout returns 200")
    void saveNightscoutConfig_ok() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        DataSourceConfigRequestDto config = DataSourceConfigRequestDto.builder()
                .dataSource(UserDataSourceConfig.DataSourceType.NIGHTSCOUT)
                .nightscoutUrl("https://nightscout.example.com")
                .nightscoutApiSecret("mysecret")
                .nightscoutApiToken("mytoken")
                .isActive(true)
                .build();

        ResponseEntity<UserDataSourceConfigDto> resp = rest.exchange(
                "/api/user/data-source-config",
                HttpMethod.POST,
                new HttpEntity<>(config, headers),
                UserDataSourceConfigDto.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
    }

    @Test
    @DisplayName("Test Nightscout endpoint with invalid URL returns error response (not 500)")
    void testNightscout_invalidUrl_returnsErrorNotServerError() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        NightscoutTestRequestDto testReq = new NightscoutTestRequestDto();
        testReq.setNightscoutUrl("not-a-valid-url");
        testReq.setNightscoutApiSecret("");
        testReq.setNightscoutApiToken("");

        ResponseEntity<NightscoutTestResponseDto> resp = rest.exchange(
                "/api/user/data-source-config/test-nightscout",
                HttpMethod.POST,
                new HttpEntity<>(testReq, headers),
                NightscoutTestResponseDto.class);

        // Should be 200 with ok=false, NOT a 500
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isOk(), "Expected ok=false for invalid URL");
    }

    @Test
    @DisplayName("Fetch stored chart data returns empty list when no data synced")
    void getStoredChartData_emptyWhenNoSync() {
        RegisterRequest req = validRegister();
        HttpHeaders headers = authedHeaders(req);

        ResponseEntity<List<NightscoutEntryDto>> resp = rest.exchange(
                "/api/nightscout/chart-data",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<NightscoutEntryDto>>() {});

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isEmpty(), "Expected empty chart data for new user");
    }
}
