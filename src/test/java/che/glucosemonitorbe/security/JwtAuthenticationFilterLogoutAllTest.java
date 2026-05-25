package che.glucosemonitorbe.security;

import che.glucosemonitorbe.service.TokenBlacklistService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterLogoutAllTest {

    @Mock private JwtTokenProvider tokenProvider;
    @Mock private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;
    @Mock private TokenBlacklistService tokenBlacklistService;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @Test
    void rejectsAccessTokenWhenUserGloballyLoggedOut() throws Exception {
        String jwt = "valid-access-token";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notes");
        request.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(tokenProvider.validateToken(jwt)).thenReturn(true);
        when(tokenProvider.isRefreshToken(jwt)).thenReturn(false);
        when(tokenBlacklistService.isTokenBlacklisted(jwt)).thenReturn(false);
        when(tokenProvider.getUsernameFromToken(jwt)).thenReturn("alice");
        when(tokenBlacklistService.isUserGloballyLoggedOut("alice")).thenReturn(true);

        SecurityContextHolder.clearContext();
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokenBlacklistService).isUserGloballyLoggedOut("alice");
    }
}
