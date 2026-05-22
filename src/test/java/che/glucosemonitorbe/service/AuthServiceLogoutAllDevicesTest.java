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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Regression tests for AuthService.logoutAllDevices (BE-7).
 *
 * The current implementation is a no-op stub: it does NOT call
 * tokenBlacklistService.blacklistToken and simply returns success.
 * These tests document the expected contract so they will FAIL until
 * the stub is replaced with a real implementation.
 */
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

    // ── BE-7: logoutAllDevices is a no-op stub ────────────────────────────────

    /**
     * BUG: BE-7 — logoutAllDevices does not call blacklistToken on any token.
     *
     * A real implementation must query all active sessions for the user and
     * blacklist every token.  This test verifies that blacklistToken is called
     * at least once — it will FAIL against the current stub.
     */
    @Test
    void be7_logoutAllDevices_mustCallBlacklistTokenAtLeastOnce() {
        authService.logoutAllDevices("someuser");

        // BUG: the stub never calls blacklistToken — this verify will FAIL
        verify(tokenBlacklistService, atLeastOnce()).blacklistToken(anyString());
    }

    /**
     * BUG: BE-7 — the stub returns success even without blacklisting anything.
     *
     * The response alone being "success" is misleading because no real work is
     * done.  This test documents that if no tokens are blacklisted the service
     * must return an error or a response indicating partial/no action.
     *
     * Currently this PASSES (the stub always returns success), which is the wrong
     * behaviour — included here to make the semantic bug visible.
     */
    @Test
    void be7_logoutAllDevices_stubAlwaysReturnsSuccess_withoutBlacklisting() {
        LogoutResponse response = authService.logoutAllDevices("someuser");

        // The stub returns success=true but does no real work — document the gap
        assertThat(response.isSuccess()).isTrue();

        // The real assertion: blacklistToken must have been called.
        // This is the same as the previous test; having it here makes the failure
        // message clearer when reading the report.
        verify(tokenBlacklistService, atLeastOnce()).blacklistToken(anyString());
    }

}
