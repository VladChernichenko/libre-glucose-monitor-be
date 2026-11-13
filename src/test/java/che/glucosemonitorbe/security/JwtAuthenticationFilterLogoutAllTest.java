package che.glucosemonitorbe.security;

import che.glucosemonitorbe.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterLogoutAllTest {

    @Mock private JwtTokenProvider tokenProvider;
    @Mock private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;
    @Mock private TokenBlacklistService tokenBlacklistService;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @Test
    void rejectsAccessTokenIssuedBeforeLogoutAll() throws Exception {
        String jwt = "valid-access-token";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notes");
        request.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // Single-parse design (BE-M3): the filter reads claims once via parseClaims().
        Claims claims = mock(Claims.class);
        when(claims.get("type")).thenReturn(null); // access token, not a refresh token
        when(claims.getSubject()).thenReturn("alice");
        when(tokenProvider.parseClaims(jwt)).thenReturn(Optional.of(claims));
        when(tokenBlacklistService.isTokenBlacklisted(jwt)).thenReturn(false);
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        when(claims.get(JwtTokenProvider.ISSUED_AT_MS_CLAIM)).thenReturn(issuedAt.toEpochMilli());
        when(tokenBlacklistService.isRevokedByLogoutAll("alice", issuedAt)).thenReturn(true);

        SecurityContextHolder.clearContext();
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokenBlacklistService).isRevokedByLogoutAll("alice", issuedAt);
        assertThat(chain.getRequest()).as("request continues anonymously").isNotNull();
    }

    @Test
    void databaseFailureWhileCheckingTokenAnswers503InsteadOfAnonymous() throws Exception {
        String jwt = "valid-access-token";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notes");
        request.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        Claims claims = mock(Claims.class);
        when(tokenProvider.parseClaims(jwt)).thenReturn(Optional.of(claims));
        when(tokenBlacklistService.isTokenBlacklisted(jwt))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        SecurityContextHolder.clearContext();
        filter.doFilter(request, response, chain);

        // A 401 here would tell the client a valid session is dead; 503 tells it to retry.
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(chain.getRequest()).as("chain must not continue").isNull();
    }
}
