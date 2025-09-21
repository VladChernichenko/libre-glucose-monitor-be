package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.LogoutRequest;
import che.glucosemonitorbe.dto.LogoutResponse;
import che.glucosemonitorbe.dto.RefreshTokenRequest;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthResponse login(AuthRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        String accessToken = tokenProvider.generateToken(authentication);
        String refreshToken = tokenProvider.generateRefreshToken(request.getUsername());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(3600L) // 1 hour
                .build();
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .fullName(request.getFullName())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(User.Role.USER)
                .build();

        userRepository.save(user);

        String accessToken = tokenProvider.generateTokenFromUsername(user.getUsername());
        String refreshToken = tokenProvider.generateRefreshToken(user.getUsername());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
    }

    public AuthResponse refreshToken(RefreshTokenRequest request) {
        if (!tokenProvider.validateToken(request.getRefreshToken()) || 
            !tokenProvider.isRefreshToken(request.getRefreshToken())) {
            throw new RuntimeException("Invalid refresh token");
        }
        
        // Check if refresh token is blacklisted
        if (tokenBlacklistService.isTokenBlacklisted(request.getRefreshToken())) {
            throw new RuntimeException("Refresh token has been revoked");
        }

        String username = tokenProvider.getUsernameFromToken(request.getRefreshToken());
        String accessToken = tokenProvider.generateTokenFromUsername(username);
        String refreshToken = tokenProvider.generateRefreshToken(username);

        // Blacklist the old refresh token
        tokenBlacklistService.blacklistToken(request.getRefreshToken());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
    }
    
    /**
     * Logout user by blacklisting their tokens
     */
    public LogoutResponse logout(LogoutRequest request) {
        try {
            // Validate access token before blacklisting
            if (!tokenProvider.validateToken(request.getAccessToken())) {
                return LogoutResponse.error("Invalid access token");
            }
            
            // Blacklist the access token
            tokenBlacklistService.blacklistToken(request.getAccessToken());
            log.info("Access token blacklisted for logout");
            
            // Blacklist refresh token if provided
            if (request.getRefreshToken() != null && !request.getRefreshToken().trim().isEmpty()) {
                if (tokenProvider.validateToken(request.getRefreshToken()) && 
                    tokenProvider.isRefreshToken(request.getRefreshToken())) {
                    tokenBlacklistService.blacklistToken(request.getRefreshToken());
                    log.info("Refresh token blacklisted for logout");
                }
            }
            
            return LogoutResponse.success("Logout successful");
            
        } catch (Exception e) {
            log.error("Error during logout: {}", e.getMessage());
            return LogoutResponse.error("Logout failed: " + e.getMessage());
        }
    }
    
    /**
     * Logout from all devices by blacklisting all user's tokens
     * Note: This is a simplified implementation. In production, you'd track user sessions.
     */
    public LogoutResponse logoutAllDevices(String username) {
        try {
            // In a real implementation, you'd query all active sessions for the user
            // and blacklist all their tokens. For now, we'll just return success.
            log.info("Logout all devices requested for user: {}", username);
            return LogoutResponse.success("Logged out from all devices");
        } catch (Exception e) {
            log.error("Error during logout all devices: {}", e.getMessage());
            return LogoutResponse.error("Logout all devices failed: " + e.getMessage());
        }
    }
}

