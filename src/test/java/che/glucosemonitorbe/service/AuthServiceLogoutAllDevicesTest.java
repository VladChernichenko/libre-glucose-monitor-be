package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.LogoutResponse;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** Regression tests for AuthService.logoutAllDevices (BE-7). */
@ExtendWith(MockitoExtension.class)
class AuthServiceLogoutAllDevicesTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider tokenProvider;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                authenticationManager, userRepository, passwordEncoder,
                tokenProvider, tokenBlacklistService);
    }

    @Test
    void be7_logoutAllDevices_registersGlobalLogoutSentinel() {
        LogoutResponse response = authService.logoutAllDevices("someuser");

        assertThat(response.isSuccess()).isTrue();
        verify(tokenBlacklistService).blacklistAllDevicesForUser("someuser");
    }

}
