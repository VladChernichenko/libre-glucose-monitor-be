package che.glucosemonitorbe.sick;

import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone MockMvc test (same style as HypoEventControllerTest) with a mocked service. */
@ExtendWith(MockitoExtension.class)
class SickModeControllerTest {

    @Mock SickModeService sickModeService;
    @Mock UserService userService;

    private final UUID userId = UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("alice", null);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userService.getUserByUsername("alice")).thenReturn(UserDto.builder().id(userId).username("alice").build());
        mockMvc = MockMvcBuilders.standaloneSetup(new SickModeController(sickModeService, userService)).build();
    }

    private static SickModeStatusDTO active() {
        return new SickModeStatusDTO(true, SickSeverity.MODERATE, LocalDateTime.of(2026, 11, 16, 6, 0),
                1, -30, 15, false, null);
    }

    @Test
    void getReturnsTheStatus() throws Exception {
        when(sickModeService.status(userId)).thenReturn(active());
        mockMvc.perform(get("/api/sick-mode").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.severity").value("MODERATE"))
                .andExpect(jsonPath("$.isfPercent").value(-30));
    }

    @Test
    void startPassesSeverityAndSince() throws Exception {
        when(sickModeService.start(userId, SickSeverity.MILD, Since.THIS_MORNING)).thenReturn(active());
        mockMvc.perform(post("/api/sick-mode/start").principal(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"MILD\",\"since\":\"THIS_MORNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayNumber").value(1));
    }

    @Test
    void severityChangeIsAPut() throws Exception {
        when(sickModeService.changeSeverity(userId, SickSeverity.SEVERE)).thenReturn(active());
        mockMvc.perform(put("/api/sick-mode/severity").principal(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"SEVERE\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void confirmAndEndDelegate() throws Exception {
        when(sickModeService.confirm(userId)).thenReturn(active());
        when(sickModeService.end(userId, "USER")).thenReturn(SickModeStatusDTO.inactive());
        mockMvc.perform(post("/api/sick-mode/confirm").principal(auth)).andExpect(status().isOk());
        mockMvc.perform(post("/api/sick-mode/end").principal(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        verify(sickModeService).confirm(userId);
    }

    @Test
    void anIllegalArgumentBecomesBadRequest() throws Exception {
        when(sickModeService.start(userId, SickSeverity.MILD, Since.NOW))
                .thenThrow(new IllegalArgumentException("Sick mode is already active"));
        mockMvc.perform(post("/api/sick-mode/start").principal(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"MILD\",\"since\":\"NOW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Sick mode is already active"));
    }
}
