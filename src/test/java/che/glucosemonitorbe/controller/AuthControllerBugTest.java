package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.LogoutRequest;
import che.glucosemonitorbe.dto.LogoutResponse;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer regression tests for AuthController covering:
 * - A2: POST /api/auth/register must return HTTP 201 Created, not 200 OK
 * - A3: POST /api/auth/logout must return a non-200 status when the service
 *       indicates failure (success=false), not always HTTP 200
 * - A5: POST /api/auth/logout-all is an unimplemented stub and must return
 *       HTTP 501 Not Implemented until the full implementation is wired
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerBugTest {

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController authController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -- A2: register must return 201 Created ---------------------------------

    /**
     * BUG: A2 - AuthController.register calls ResponseEntity.ok() which returns
     * HTTP 200.  REST conventions require HTTP 201 Created for resource creation.
     * This test FAILS until the controller is changed to use
     * ResponseEntity.status(HttpStatus.CREATED).body(response).
     */
    @Test
    void a2_register_mustReturn201Created() throws Exception {
        AuthResponse authResponse = AuthResponse.builder()
                .accessToken("access-token")
                .refreshToken("refresh-token")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
        when(authService.register(any(RegisterRequest.class))).thenReturn(authResponse);

        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setUsername("newuser");
        registerRequest.setEmail("new@example.com");
        registerRequest.setFullName("Test User");
        registerRequest.setPassword("password123");

        // BUG: current code returns 200; this assertion expects 201 -> FAILS
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());
    }

    // -- A3: logout must return error status when service returns failure ------

    /**
     * BUG: A3 - AuthController.logout wraps the service response in
     * ResponseEntity.ok() unconditionally.  When the service returns
     * LogoutResponse.error(...) (success=false), the HTTP status must reflect
     * that failure (e.g. 400 or 401), not 200 OK.
     *
     * This test FAILS because the current controller always returns 200.
     */
    @Test
    void a3_logout_whenServiceReturnsError_mustNotReturn200() throws Exception {
        when(authService.logout(any(LogoutRequest.class)))
                .thenReturn(LogoutResponse.error("bad token"));

        LogoutRequest logoutRequest = new LogoutRequest();
        logoutRequest.setAccessToken("invalid-access-token");

        // BUG: current code returns 200 regardless of success flag -> this FAILS
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().is4xxClientError());
    }

    /**
     * A3 companion - on a successful logout, HTTP 200 is correct.
     * This regression test ensures the success path is not broken when A3 is fixed.
     */
    @Test
    void a3_logout_whenServiceReturnsSuccess_returns200() throws Exception {
        when(authService.logout(any(LogoutRequest.class)))
                .thenReturn(LogoutResponse.success("Logout successful"));

        LogoutRequest logoutRequest = new LogoutRequest();
        logoutRequest.setAccessToken("valid-access-token");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isOk());
    }

    @Test
    void a5_logoutAllDevices_whenAuthenticated_returns200() throws Exception {
        when(authService.logoutAllDevices(anyString()))
                .thenReturn(LogoutResponse.success("Logged out from all devices"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));

        mockMvc.perform(post("/api/auth/logout-all")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void a5_logoutAllDevices_whenUnauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
