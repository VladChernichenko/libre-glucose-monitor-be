package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InsulinCalculatorControllerTest {

    @Mock private InsulinCalculatorService insulinCalculatorService;
    @Mock private FeatureToggleService featureToggleService;
    @Mock private UserService userService;

    private InsulinCalculatorController controller;
    private UUID authUserId;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        controller = new InsulinCalculatorController(
                insulinCalculatorService, featureToggleService, userService);
        authUserId = UUID.randomUUID();
        // lenient: the @Valid tests below are rejected before the controller body runs,
        // so this stub is legitimately unused in those cases.
        lenient().when(userService.getUserByUsername("alice")).thenReturn(
                UserDto.builder().id(authUserId).username("alice").build());
        auth = new UsernamePasswordAuthenticationToken("alice", null);
    }

    @Test
    @DisplayName("BE-5: foreign body userId is overwritten with authenticated user")
    void calculate_ignoresClientUserId() {
        when(featureToggleService.shouldUseBackend("insulin-calculator")).thenReturn(true);
        when(featureToggleService.shouldMigrate("insulin-calculator", authUserId.toString())).thenReturn(true);
        when(insulinCalculatorService.calculateRecommendedInsulin(any()))
                .thenReturn(InsulinCalculationResponse.builder()
                        .recommendedInsulin(2.0)
                        .calculationTime(LocalDateTime.now())
                        .build());

        UUID foreignId = UUID.randomUUID();
        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .carbs(48.0)
                .currentGlucose(8.0)
                .targetGlucose(5.5)
                .activeInsulin(0.0)
                .userId(foreignId.toString())
                .build();

        ResponseEntity<?> resp = controller.calculateInsulin(request, auth);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<InsulinCalculationRequest> captor =
                ArgumentCaptor.forClass(InsulinCalculationRequest.class);
        verify(insulinCalculatorService).calculateRecommendedInsulin(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(authUserId.toString());
        assertThat(captor.getValue().getUserId()).isNotEqualTo(foreignId.toString());
    }

    @Test
    @DisplayName("BE-5: status uses authenticated user for migration key")
    void status_usesAuthenticatedUser() {
        when(featureToggleService.shouldUseBackend("insulin-calculator")).thenReturn(true);
        when(featureToggleService.shouldMigrate("insulin-calculator", authUserId.toString())).thenReturn(true);
        when(featureToggleService.getMigrationPercent("insulin-calculator")).thenReturn(100);
        when(featureToggleService.isBackendModeEnabled()).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.getStatus(auth);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).containsEntry("shouldMigrate", true);
        verify(featureToggleService).shouldMigrate("insulin-calculator", authUserId.toString());
    }

    // -- C2: request validation and the 422 wire contract ----------------------

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Validation only fires through the MVC stack. Calling the controller method directly
     * (as the BE-5 tests above do) bypasses @Valid entirely.
     */
    private MockMvc mvc() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        }
        return mockMvc;
    }

    @Test
    @DisplayName("C2: a mg/dL payload is rejected as an implausible unit, not dosed")
    void mgPerDlPayload_returns422_withUnitReason() throws Exception {
        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .carbs(60.0).currentGlucose(180.0).targetGlucose(100.0).activeInsulin(0.0).build();

        mvc().perform(post("/api/insulin/calculate")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GLUCOSE_IMPLAUSIBLE_UNIT"));
    }

    @Test
    @DisplayName("C2: targetGlucose of 0 is rejected")
    void zeroTargetGlucose_returns422() throws Exception {
        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .carbs(60.0).currentGlucose(8.0).targetGlucose(0.0).activeInsulin(0.0).build();

        mvc().perform(post("/api/insulin/calculate")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("C2: missing carbs is rejected")
    void missingCarbs_returns422() throws Exception {
        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .currentGlucose(8.0).targetGlucose(5.5).activeInsulin(0.0).build();

        mvc().perform(post("/api/insulin/calculate")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("C2: a service refusal is not swallowed into a generic 400")
    void serviceRefusal_propagates() {
        when(featureToggleService.shouldUseBackend("insulin-calculator")).thenReturn(true);
        when(featureToggleService.shouldMigrate(eq("insulin-calculator"), any())).thenReturn(true);
        when(insulinCalculatorService.calculateRecommendedInsulin(any()))
                .thenThrow(new DosingRefusedException(
                        DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD, "currentGlucose=3.0"));

        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .carbs(60.0).currentGlucose(4.5).targetGlucose(5.5).activeInsulin(0.0).build();

        assertThatThrownBy(() -> controller.calculateInsulin(request, auth))
                .isInstanceOf(DosingRefusedException.class);
    }
}
