package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.service.LibreLinkUpService;
import che.glucosemonitorbe.service.UserDataSourceConfigService;
import che.glucosemonitorbe.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for LibreLinkUpController — covers all 9 endpoints (happy-path + error path).
 * Uses MockMvc standalone setup with mocked services; no Spring context overhead.
 */
@ExtendWith(MockitoExtension.class)
class LibreLinkUpControllerTest {

    @Mock LibreLinkUpService libreLinkUpService;
    @Mock UserService userService;
    @Mock UserDataSourceConfigService dataSourceConfigService;

    @InjectMocks LibreLinkUpController controller;

    private MockMvc mockMvc;
    private Authentication auth;
    private UUID userId;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        userId = UUID.randomUUID();
        UserDto userDto = UserDto.builder().id(userId).username("testuser").build();
        when(userService.getUserByUsername("testuser")).thenReturn(userDto);
        auth = new UsernamePasswordAuthenticationToken(
                "testuser", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    // ── POST /api/libre/auth/login ────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/login — success returns 200 with token")
    void authenticate_success_returns200() throws Exception {
        LibreAuthRequest req = new LibreAuthRequest("test@example.com", "secret");
        LibreAuthResponse resp = new LibreAuthResponse("tok-123", System.currentTimeMillis() + 86400_000L);
        when(libreLinkUpService.authenticate(any(), eq(userId))).thenReturn(resp);

        mockMvc.perform(post("/api/libre/auth/login")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("tok-123"));
    }

    @Test
    @DisplayName("POST /auth/login — service error returns 400")
    void authenticate_serviceThrows_returns400() throws Exception {
        LibreAuthRequest req = new LibreAuthRequest("bad@example.com", "wrong");
        when(libreLinkUpService.authenticate(any(), any())).thenThrow(new RuntimeException("Auth failed"));

        mockMvc.perform(post("/api/libre/auth/login")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Authentication failed")));
    }

    @Test
    @DisplayName("POST /auth/login — persists LLU config; DataSource exception is swallowed")
    void authenticate_dataSourceConfigFails_stillReturns200() throws Exception {
        LibreAuthRequest req = new LibreAuthRequest("test@example.com", "secret");
        LibreAuthResponse resp = new LibreAuthResponse("tok-ok", System.currentTimeMillis() + 86400_000L);
        when(libreLinkUpService.authenticate(any(), eq(userId))).thenReturn(resp);
        doThrow(new RuntimeException("DB error"))
                .when(dataSourceConfigService).upsertLibreConfig(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/libre/auth/login")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("tok-ok"));
    }

    // ── GET /api/libre/connections ────────────────────────────────────────────

    @Test
    @DisplayName("GET /connections — returns list and persists patientId")
    void getConnections_success_returns200AndPersistsPatientId() throws Exception {
        LibreConnection conn = new LibreConnection("John Doe", "patient-1", "active", "2025-01-01T00:00:00Z");
        when(libreLinkUpService.getConnections(userId)).thenReturn(List.of(conn));

        mockMvc.perform(get("/api/libre/connections").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patientId").value("patient-1"));

        verify(dataSourceConfigService).upsertLibreConfig(eq(userId), isNull(), isNull(), isNull(), eq("patient-1"));
    }

    @Test
    @DisplayName("GET /connections — empty list skips patientId persist")
    void getConnections_empty_skipsPatientIdPersist() throws Exception {
        when(libreLinkUpService.getConnections(userId)).thenReturn(List.of());

        mockMvc.perform(get("/api/libre/connections").principal(auth))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(dataSourceConfigService, never()).upsertLibreConfig(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /connections — connection with null patientId skips persist")
    void getConnections_nullPatientId_skipsPatientIdPersist() throws Exception {
        LibreConnection conn = new LibreConnection("Jane", null, "active", "now");
        when(libreLinkUpService.getConnections(userId)).thenReturn(List.of(conn));

        mockMvc.perform(get("/api/libre/connections").principal(auth))
                .andExpect(status().isOk());

        verify(dataSourceConfigService, never()).upsertLibreConfig(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /connections — patientId persist failure is swallowed")
    void getConnections_dataSourceConfigFails_stillReturns200() throws Exception {
        LibreConnection conn = new LibreConnection("John", "patient-2", "active", "now");
        when(libreLinkUpService.getConnections(userId)).thenReturn(List.of(conn));
        doThrow(new RuntimeException("DB error"))
                .when(dataSourceConfigService).upsertLibreConfig(any(), any(), any(), any(), any());

        mockMvc.perform(get("/api/libre/connections").principal(auth))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /connections — service error returns 400")
    void getConnections_serviceThrows_returns400() throws Exception {
        when(libreLinkUpService.getConnections(any())).thenThrow(new RuntimeException("Not authenticated"));

        mockMvc.perform(get("/api/libre/connections").principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed to fetch connections")));
    }

    // ── GET /api/libre/connections/{patientId}/graph ──────────────────────────

    @Test
    @DisplayName("GET /connections/{id}/graph — returns 200 with glucose data")
    void getGlucoseData_success_returns200() throws Exception {
        LibreGlucoseData data = new LibreGlucoseData("p1", List.of(), "start", "end", "mmol/L");
        when(libreLinkUpService.getGlucoseData(eq("p1"), eq(1), eq(userId))).thenReturn(data);

        mockMvc.perform(get("/api/libre/connections/p1/graph").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value("p1"));
    }

    @Test
    @DisplayName("GET /connections/{id}/graph — service error returns 400")
    void getGlucoseData_serviceThrows_returns400() throws Exception {
        when(libreLinkUpService.getGlucoseData(any(), anyInt(), any()))
                .thenThrow(new RuntimeException("Network error"));

        mockMvc.perform(get("/api/libre/connections/p1/graph").principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed to fetch glucose data")));
    }

    // ── GET /api/libre/connections/{patientId}/current ────────────────────────

    @Test
    @DisplayName("GET /connections/{id}/current — returns 200 with reading")
    void getCurrentGlucose_success_returns200() throws Exception {
        LibreGlucoseReading reading = new LibreGlucoseReading(
                new Date(), 7.5, 4, "→", "normal", "mmol/L", new Date());
        when(libreLinkUpService.getCurrentGlucose(eq("p1"), eq(userId))).thenReturn(reading);

        mockMvc.perform(get("/api/libre/connections/p1/current").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(7.5));
    }

    @Test
    @DisplayName("GET /connections/{id}/current — service error returns 400")
    void getCurrentGlucose_serviceThrows_returns400() throws Exception {
        when(libreLinkUpService.getCurrentGlucose(any(), any()))
                .thenThrow(new RuntimeException("No data"));

        mockMvc.perform(get("/api/libre/connections/p1/current").principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed to fetch current glucose")));
    }

    // ── GET /api/libre/connections/{patientId}/history ────────────────────────

    @Test
    @DisplayName("GET /connections/{id}/history — returns 200 with default days")
    void getGlucoseHistory_success_returns200() throws Exception {
        LibreGlucoseData data = new LibreGlucoseData("p1", List.of(), null, null, "mmol/L");
        when(libreLinkUpService.getGlucoseHistory(eq("p1"), eq(7), isNull(), isNull(), eq(userId)))
                .thenReturn(data);

        mockMvc.perform(get("/api/libre/connections/p1/history").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value("p1"));
    }

    @Test
    @DisplayName("GET /connections/{id}/history — custom days and date range forwarded")
    void getGlucoseHistory_customParams_forwardedToService() throws Exception {
        LibreGlucoseData data = new LibreGlucoseData("p1", List.of(), "2025-01-01", "2025-01-14", "mmol/L");
        when(libreLinkUpService.getGlucoseHistory(eq("p1"), eq(14), eq("2025-01-01"), eq("2025-01-14"), eq(userId)))
                .thenReturn(data);

        mockMvc.perform(get("/api/libre/connections/p1/history")
                        .param("days", "14")
                        .param("startDate", "2025-01-01")
                        .param("endDate", "2025-01-14")
                        .principal(auth))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /connections/{id}/history — service error returns 400")
    void getGlucoseHistory_serviceThrows_returns400() throws Exception {
        when(libreLinkUpService.getGlucoseHistory(any(), anyInt(), any(), any(), any()))
                .thenThrow(new RuntimeException("History error"));

        mockMvc.perform(get("/api/libre/connections/p1/history").principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed to fetch glucose history")));
    }

    // ── GET /api/libre/connections/{patientId}/sensor ─────────────────────────

    @Test
    @DisplayName("GET /connections/{id}/sensor — returns 200 with sensor info")
    void getSensorInfo_success_returns200() throws Exception {
        LibreSensorInfo info = new LibreSensorInfo(
                "FA-12345", "FreeStyle Libre 3", new Date(), new Date(), 5, 14, "active", 9);
        when(libreLinkUpService.getSensorInfo(eq("p1"), eq(userId))).thenReturn(info);

        mockMvc.perform(get("/api/libre/connections/p1/sensor").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("FA-12345"))
                .andExpect(jsonPath("$.sensorModel").value("FreeStyle Libre 3"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.daysRemaining").value(9));
    }

    @Test
    @DisplayName("GET /connections/{id}/sensor — service error returns 400")
    void getSensorInfo_serviceThrows_returns400() throws Exception {
        when(libreLinkUpService.getSensorInfo(any(), any()))
                .thenThrow(new RuntimeException("Sensor error"));

        mockMvc.perform(get("/api/libre/connections/p1/sensor").principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed to fetch sensor info")));
    }

}
