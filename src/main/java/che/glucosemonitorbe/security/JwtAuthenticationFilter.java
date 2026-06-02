package che.glucosemonitorbe.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import che.glucosemonitorbe.service.TokenBlacklistService;
import org.slf4j.MDC;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);
            if (StringUtils.hasText(jwt)) {
                try {
                    authenticateFromToken(jwt, request);
                } catch (Exception ex) {
                    // Never let an auth-setup failure break the chain; the request just stays anonymous.
                    log.error("Could not set user authentication for {}: {}",
                            request.getRequestURI(), ex.getMessage());
                }
            } else {
                log.debug("No JWT token found for request: {}", request.getRequestURI());
            }

            filterChain.doFilter(request, response);
        } finally {
            // BE-M3: always clear the per-request MDC key, even if a downstream filter throws,
            // so pooled threads never leak one user's id into the next request's logs.
            MDC.remove("userId");
        }
    }

    /** Parse the token once and, if it authorises a non-refresh, non-revoked session, set the context. */
    private void authenticateFromToken(String jwt, HttpServletRequest request) {
        var claimsOpt = tokenProvider.parseClaims(jwt);
        if (claimsOpt.isEmpty()) {
            log.warn("JWT authentication failed for {}: invalid token", request.getRequestURI());
            return;
        }

        var claims = claimsOpt.get();
        boolean isRefreshToken = "refresh".equals(claims.get("type"));
        boolean isBlacklisted = tokenBlacklistService.isTokenBlacklisted(jwt);
        if (isRefreshToken || isBlacklisted) {
            log.warn("JWT rejected for {}: refresh={}, blacklisted={}",
                    request.getRequestURI(), isRefreshToken, isBlacklisted);
            return;
        }

        String username = claims.getSubject();
        if (tokenBlacklistService.isUserGloballyLoggedOut(username)) {
            log.warn("JWT rejected for {}: user logged out from all devices", username);
            return;
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        MDC.put("userId", username);
        log.debug("Successfully authenticated user: {}", username);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

