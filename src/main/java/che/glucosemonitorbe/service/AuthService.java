package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.exception.InvalidTokenException;
import che.glucosemonitorbe.exception.UsernameAlreadyExistsException;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
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
                .expiresIn(tokenProvider.getAccessTokenExpirySeconds()) // 1 hour
                .build();
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            // BE-8 fix: throw typed exception so GlobalExceptionHandler returns HTTP 409
            throw new UsernameAlreadyExistsException("Username already exists: " + request.getUsername());
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            // BE-8 fix: same - email conflict also maps to HTTP 409
            throw new UsernameAlreadyExistsException("Email already in use: " + request.getEmail());
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
                .expiresIn(tokenProvider.getAccessTokenExpirySeconds())
                .build();
    }

    public AuthResponse refreshToken(RefreshTokenRequest request) {
        if (!tokenProvider.validateToken(request.getRefreshToken()) ||
            !tokenProvider.isRefreshToken(request.getRefreshToken())) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        Claims claims = tokenProvider.parseClaims(request.getRefreshToken())
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));
        String username = claims.getSubject();
        if (tokenBlacklistService.isRevokedByLogoutAll(username, JwtTokenProvider.issuedAt(claims))) {
            throw new InvalidTokenException("Session revoked - please log in again");
        }

        // Atomically spend this refresh token (DB-level INSERT ... ON CONFLICT DO NOTHING) *before*
        // minting, so concurrent requests cannot both treat it as unused. A token that was rotated
        // only moments ago is tolerated once more: the usual cause is a refresh response the client
        // never received, and rejecting it would sign a legitimate user out.
        TokenBlacklistService.RotationResult rotation =
                tokenBlacklistService.rotateRefreshToken(request.getRefreshToken());
        if (rotation == TokenBlacklistService.RotationResult.REJECTED) {
            throw new InvalidTokenException("Refresh token has been revoked");
        }
        if (rotation == TokenBlacklistService.RotationResult.REUSED_WITHIN_GRACE) {
            log.info("Refresh token for {} reused within the rotation grace period", username);
        }

        String accessToken = tokenProvider.generateTokenFromUsername(username);
        String refreshToken = tokenProvider.generateRefreshToken(username);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(tokenProvider.getAccessTokenExpirySeconds())
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
            // Moves the user's "tokens valid after" watermark to now: every token issued before
            // this call is rejected from here on, including after the user logs in again.
            log.info("Logout all devices requested for user: {}", username);
            tokenBlacklistService.blacklistAllDevicesForUser(username);
            return LogoutResponse.success("Logged out from all devices");
        } catch (Exception e) {
            log.error("Error during logout all devices: {}", e.getMessage());
            return LogoutResponse.error("Logout all devices failed: " + e.getMessage());
        }
    }
}
