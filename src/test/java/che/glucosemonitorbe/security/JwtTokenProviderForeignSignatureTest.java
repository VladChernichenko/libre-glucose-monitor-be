package che.glucosemonitorbe.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A token signed with a different secret (e.g. the client still holds tokens minted before the
 * server's JWT_SECRET changed) must be reported as invalid, not thrown. jjwt signals this with
 * {@code io.jsonwebtoken.security.SignatureException}, which is NOT a
 * {@code java.lang.SecurityException}; letting it escape turned /api/auth/refresh into a 500
 * instead of the 401 that tells the client to log in again.
 */
class JwtTokenProviderForeignSignatureTest {

    private static final String SERVER_SECRET =
            "dev-only-insecure-jwt-secret-CHANGE-ME-do-not-use-in-production-0123456789";
    private static final String OTHER_SECRET =
            "some-other-secret-the-server-used-before-it-was-rotated-0123456789-abcdefgh";

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(tokenProvider, "jwtSecret", SERVER_SECRET);
        ReflectionTestUtils.setField(tokenProvider, "jwtExpirationInMs", 3_600_000L);
        ReflectionTestUtils.setField(tokenProvider, "refreshExpirationInMs", 3_600_000L);
    }

    @Test
    @DisplayName("Token signed with another secret is invalid rather than an unhandled exception")
    void tokenSignedWithOtherSecret_isRejectedWithoutThrowing() {
        Date now = new Date();
        String foreign = Jwts.builder()
                .subject("alice")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000L))
                .claim("type", "refresh")
                .signWith(Keys.hmacShaKeyFor(OTHER_SECRET.getBytes()), Jwts.SIG.HS512)
                .compact();

        assertThat(tokenProvider.parseClaims(foreign)).isEmpty();
        assertThat(tokenProvider.validateToken(foreign)).isFalse();
        assertThat(tokenProvider.isRefreshToken(foreign)).isFalse();
    }
}
