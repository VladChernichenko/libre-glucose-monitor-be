package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.LogoutRequest;
import che.glucosemonitorbe.dto.LogoutResponse;
import che.glucosemonitorbe.dto.RefreshTokenRequest;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.exception.UsernameAlreadyExistsException;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService covering:
 * - BE-8: register throws UsernameAlreadyExistsException (not RuntimeException)
 *         for duplicate username or duplicate email
 * - login returns tokens
 * - logout blacklists tokens
 * - logoutAllDevices succeeds
 */
class AuthServiceTest {

    private AuthenticationManager authenticationManager;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider tokenProvider;
    private TokenBlacklistService tokenBlacklistService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        userRepository        = mock(UserRepository.class);
        passwordEncoder       = mock(PasswordEncoder.class);
        tokenProvider         = mock(JwtTokenProvider.class);
        tokenBlacklistService = mock(TokenBlacklistService.class);

        authService = new AuthService(
                authenticationManager, userRepository, passwordEncoder,
                tokenProvider, tokenBlacklistService);

        // Sensible defaults
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-pw");
        when(tokenProvider.generateToken(any())).thenReturn("access-token");
        when(tokenProvider.generateTokenFromUsername(anyString())).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
    }

    // ── BE-8: duplicate username ───────────────────────────────────────────────

    /**
     * BE-8 regression: before the fix, register threw RuntimeException for duplicate
     * username. After the fix it must throw UsernameAlreadyExistsException so the
     * GlobalExceptionHandler can return HTTP 409 instead of 400.
     */
    @Test
    void register_duplicateUsername_throwsUsernameAlreadyExistsException() {
        when(userRepository.existsByUsername("existing_user")).thenReturn(true);

        RegisterRequest request = registerRequest("existing_user", "new@example.com");

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(UsernameAlreadyExistsException.class)
                .hasMessageContaining("existing_user");
    }

    @Test
    void register_duplicateUsername_isNotGenericRuntimeException() {
        when(userRepository.existsByUsername("taken")).thenReturn(true);

        RegisterRequest request = registerRequest("taken", "new@example.com");

        // Must NOT be a plain RuntimeException — that would result in HTTP 400
        assertThatThrownBy(() -> authService.register(request))
                .isNotExactlyInstanceOf(RuntimeException.class);
    }

    // ── BE-8: duplicate email ──────────────────────────────────────────────────

    @Test
    void register_duplicateEmail_throwsUsernameAlreadyExistsException() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        RegisterRequest request = registerRequest("newuser", "taken@example.com");

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(UsernameAlreadyExistsException.class)
                .hasMessageContaining("taken@example.com");
    }

    @Test
    void register_duplicateEmail_isNotGenericRuntimeException() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest("newuser", "taken@example.com")))
                .isNotExactlyInstanceOf(RuntimeException.class);
    }

    // ── happy-path registration ────────────────────────────────────────────────

    @Test
    void register_newUser_returnsTokens() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

        AuthResponse response = authService.register(registerRequest("newuser", "new@example.com"));

        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        verify(userRepository).save(any(User.class));
    }

    // ── login ──────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returnsTokens() {
        Authentication auth = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(auth);

        AuthRequest request = new AuthRequest();
        request.setUsername("user");
        request.setPassword("pass");

        AuthResponse response = authService.login(request);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
    }

    // ── logout ─────────────────────────────────────────────────────────────────

    @Test
    void logout_validAccessToken_blacklistsToken() {
        when(tokenProvider.validateToken("valid-access")).thenReturn(true);

        LogoutRequest req = new LogoutRequest();
        req.setAccessToken("valid-access");

        LogoutResponse resp = authService.logout(req);

        assertThat(resp.isSuccess()).isTrue();
        verify(tokenBlacklistService).blacklistToken("valid-access");
    }

    @Test
    void logout_invalidAccessToken_returnsError() {
        when(tokenProvider.validateToken("bad-token")).thenReturn(false);

        LogoutRequest req = new LogoutRequest();
        req.setAccessToken("bad-token");

        LogoutResponse resp = authService.logout(req);

        assertThat(resp.isSuccess()).isFalse();
        verify(tokenBlacklistService, never()).blacklistToken(anyString());
    }

    @Test
    void logout_withRefreshToken_blacklistsBothTokens() {
        when(tokenProvider.validateToken(anyString())).thenReturn(true);
        when(tokenProvider.isRefreshToken("refresh-token")).thenReturn(true);

        LogoutRequest req = new LogoutRequest();
        req.setAccessToken("access-tok");
        req.setRefreshToken("refresh-token");

        authService.logout(req);

        verify(tokenBlacklistService).blacklistToken("access-tok");
        verify(tokenBlacklistService).blacklistToken("refresh-token");
    }

    // ── logoutAllDevices ───────────────────────────────────────────────────────

    @Test
    void logoutAllDevices_returnsSuccess() {
        LogoutResponse resp = authService.logoutAllDevices("user");
        assertThat(resp.isSuccess()).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RegisterRequest registerRequest(String username, String email) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setEmail(email);
        req.setPassword("password123");
        req.setFullName("Test User");
        return req;
    }
}
