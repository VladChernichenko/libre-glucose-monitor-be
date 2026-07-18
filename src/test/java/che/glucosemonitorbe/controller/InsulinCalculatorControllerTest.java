package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.dto.UserDto;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(userService.getUserByUsername("alice")).thenReturn(
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
}
