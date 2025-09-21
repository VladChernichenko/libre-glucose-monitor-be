package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.LogoutRequest;
import che.glucosemonitorbe.dto.LogoutResponse;
import che.glucosemonitorbe.dto.RefreshTokenRequest;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(@Valid @RequestBody LogoutRequest request) {
        LogoutResponse response = authService.logout(request);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/logout-all")
    public ResponseEntity<LogoutResponse> logoutAllDevices(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.ok(LogoutResponse.error("User not authenticated"));
        }
        
        String username = authentication.getName();
        LogoutResponse response = authService.logoutAllDevices(username);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Auth endpoint is working!");
    }
    
    /**
     * Helper endpoint to extract token from Authorization header for logout
     */
    @PostMapping("/logout-current")
    public ResponseEntity<LogoutResponse> logoutCurrent(HttpServletRequest request) {
        String token = getJwtFromRequest(request);
        if (!StringUtils.hasText(token)) {
            return ResponseEntity.ok(LogoutResponse.error("No token found in request"));
        }
        
        LogoutRequest logoutRequest = new LogoutRequest();
        logoutRequest.setAccessToken(token);
        
        LogoutResponse response = authService.logout(logoutRequest);
        return ResponseEntity.ok(response);
    }
    
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

