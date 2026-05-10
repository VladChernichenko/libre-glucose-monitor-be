package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.ai.AiInsightService;
import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.dto.AiAnalysisResponse;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AiInsightControllerTest {

    @Mock AiInsightService aiInsightService;
    @Mock UserService userService;
    @Spy ObjectMapper objectMapper;

    @InjectMocks AiInsightController controller;

    private MockMvc mockMvc;
    private UUID userId;
    private Authentication mockAuth;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        userId = UUID.randomUUID();
        UserDto userDto = UserDto.builder().id(userId).username("testuser").build();
        when(userService.getUserByUsername("testuser")).thenReturn(userDto);

        mockAuth = new UsernamePasswordAuthenticationToken(
                "testuser", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    @DisplayName("POST /api/ai-insights/retrospective returns 200 with analysis response")
    void retrospective_returns200() throws Exception {
        AiAnalysisResponse response = AiAnalysisResponse.builder()
                .modelId("rules-only")
                .summary("All looks good")
                .recommendations(List.of())
                .build();
        when(aiInsightService.analyzeRetrospective(any(), eq(12))).thenReturn(response);

        mockMvc.perform(post("/api/ai-insights/retrospective")
                        .principal(mockAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId").value("rules-only"))
                .andExpect(jsonPath("$.summary").value("All looks good"));
    }

    @Test
    @DisplayName("POST /api/ai-insights/retrospective without body uses default 12h window")
    void retrospective_noBody_usesDefaultWindow() throws Exception {
        when(aiInsightService.analyzeRetrospective(any(), eq(12)))
                .thenReturn(AiAnalysisResponse.builder().modelId("rules-only").build());

        mockMvc.perform(post("/api/ai-insights/retrospective")
                        .principal(mockAuth))
                .andExpect(status().isOk());

        verify(aiInsightService).analyzeRetrospective(any(), eq(12));
    }

    @Test
    @DisplayName("POST /api/ai-insights/retrospective with windowHours=6 passes correct window")
    void retrospective_customWindow_passedToService() throws Exception {
        when(aiInsightService.analyzeRetrospective(any(), eq(6)))
                .thenReturn(AiAnalysisResponse.builder().modelId("rules-only").build());

        mockMvc.perform(post("/api/ai-insights/retrospective")
                        .principal(mockAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowHours\": 6}"))
                .andExpect(status().isOk());

        verify(aiInsightService).analyzeRetrospective(any(), eq(6));
    }

    @Test
    @DisplayName("POST /api/ai-insights/retrospective/stream returns NDJSON content type")
    void retrospectiveStream_returnsNdjson() throws Exception {
        LlmGatewayService.GatewayResult gatewayResult = LlmGatewayService.GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput("")
                .latencyMs(10)
                .promptTokens(100)
                .completionTokens(50)
                .contextWindow(8192)
                .build();

        when(aiInsightService.streamRetrospectiveMarkdown(
                any(), eq(12), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(gatewayResult);

        mockMvc.perform(post("/api/ai-insights/retrospective/stream")
                        .principal(mockAuth)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/x-ndjson")));
    }

    @Test
    @DisplayName("POST /api/ai-insights/retrospective/stream response body contains done event")
    void retrospectiveStream_emitsDoneEvent() throws Exception {
        LlmGatewayService.GatewayResult gatewayResult = LlmGatewayService.GatewayResult.builder()
                .modelId("rules-only")
                .rawOutput("")
                .latencyMs(10)
                .promptTokens(200)
                .completionTokens(80)
                .contextWindow(8192)
                .build();

        when(aiInsightService.streamRetrospectiveMarkdown(
                any(), eq(12), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(gatewayResult);

        var asyncResult = mockMvc.perform(post("/api/ai-insights/retrospective/stream")
                        .principal(mockAuth)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String[] lines = body.split("\n");
        String lastLine = lines[lines.length - 1];
        assertThat(lastLine).contains("\"type\":\"done\"");
        assertThat(lastLine).contains("\"promptTokens\":200");
        assertThat(lastLine).contains("\"completionTokens\":80");
    }
}
