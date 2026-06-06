package che.glucosemonitorbe.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for JWT clock-skew tolerance in {@link JwtTokenProvider#parseClaims(String)}.
 *
 * <p>Without explicit skew configured, jjwt uses a default of 0 seconds — a token whose
 * {@code exp} is even one millisecond in the past is rejected. In practice device clocks
 * routinely drift a handful of seconds ahead of the server, so a token freshly issued
 * by the server can be perceived as already-expired by the time the client first uses it
 * on the next request.</p>
 *
 * <p>Fix: parser should tolerate up to {@link JwtTokenProvider#CLOCK_SKEW_SECONDS} of
 * skew (planned 30 s). These tests pin the contract:</p>
 * <ul>
 *   <li>Token expired by 5 s → still accepted (within skew)</li>
 *   <li>Token expired by 60 s → rejected (outside skew)</li>
 *   <li>Token still valid → accepted</li>
 * </ul>
 *
 * <p><b>RED before fix, GREEN after.</b> Until {@code .clockSkewSeconds(30)} is added
 * to the parser builder, the 5-second-expired test will fail.</p>
 */
class JwtTokenProviderClockSkewTest {

    /** Must be ≥64 bytes for HS512 (matches the dev default secret in application.yml). */
    private static final String SECRET = "dev-only-insecure-jwt-secret-CHANGE-ME-do-not-use-in-production-0123456789";

    private JwtTokenProvider tokenProvider;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(tokenProvider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(tokenProvider, "jwtExpirationInMs", 3_600_000L);
        ReflectionTestUtils.setField(tokenProvider, "refreshExpirationInMs", 30L * 24 * 60 * 60 * 1000);
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes());
    }

    @Test
    @DisplayName("Token still within validity is accepted")
    void validToken_isAccepted() {
        String token = signedToken("alice", -60_000L, +60_000L); // issued 1 min ago, expires in 1 min

        Optional<Claims> claims = tokenProvider.parseClaims(token);

        assertThat(claims).isPresent();
        assertThat(claims.get().getSubject()).isEqualTo("alice");
    }

    @Test
    @DisplayName("Token expired 5 s ago is accepted (within ±30 s clock-skew tolerance)")
    void tokenExpiredByFiveSeconds_isAcceptedWithinSkew() {
        // Issued 10 min ago; expired 5 s ago. With 30 s skew tolerance this must parse.
        String token = signedToken("alice", -600_000L, -5_000L);

        Optional<Claims> claims = tokenProvider.parseClaims(token);

        assertThat(claims)
                .as("RED-before-fix: jjwt default skew = 0 rejects this; after .clockSkewSeconds(30) it must parse")
                .isPresent();
    }

    @Test
    @DisplayName("Token expired 60 s ago is rejected (outside ±30 s clock-skew tolerance)")
    void tokenExpiredByOneMinute_isRejectedBeyondSkew() {
        String token = signedToken("alice", -600_000L, -60_000L);

        Optional<Claims> claims = tokenProvider.parseClaims(token);

        assertThat(claims)
                .as("Skew tolerance must not become a free expiration extension — 60 s outside it is rejected")
                .isEmpty();
    }

    // Note: a "tampered token is rejected" test was considered here, but it surfaces a
    // separate pre-existing bug — JwtTokenProvider.parseClaims catches java.lang.SecurityException
    // while jjwt actually throws io.jsonwebtoken.security.SignatureException (different package
    // root). That's orthogonal to the clock-skew topic and is tracked as a follow-up.

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a signed JWT with explicit issuedAt + expiration offsets relative to now.
     * Negative offsets are in the past, positive in the future.
     */
    private String signedToken(String subject, long issuedOffsetMs, long expOffsetMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date(now + issuedOffsetMs))
                .expiration(new Date(now + expOffsetMs))
                .signWith(signingKey, Jwts.SIG.HS512)
                .compact();
    }
}
