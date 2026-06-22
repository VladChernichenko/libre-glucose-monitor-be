package che.glucosemonitorbe.security;

import che.glucosemonitorbe.exception.InvalidTokenException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class JwtTokenProvider {

    /**
     * Clock-skew tolerance (seconds) applied to {@code exp} validation. Without this, even
     * a few seconds of device clock drift causes the very-fresh access token to be rejected
     * on the next request, forcing an unnecessary 401 → refresh round-trip every hour.
     */
    public static final long CLOCK_SKEW_SECONDS = 30L;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    @Value("${security.jwt.access-expiration-ms}")
    private long jwtExpirationInMs;

    @Value("${security.jwt.refresh-expiration-ms}")
    private long refreshExpirationInMs;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateToken(Authentication authentication) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        return generateTokenFromUsername(userPrincipal.getUsername());
    }

    public String generateTokenFromUsername(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationInMs);

        return Jwts.builder()
                .subject(username)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(), Jwts.SIG.HS512)
                .compact();
    }

    public String generateRefreshToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshExpirationInMs);
        return Jwts.builder()
                .subject(username)
                // jti: iat/exp serialize to whole-second NumericDate claims and HS512 signing is
                // deterministic, so two tokens minted for the same user within the same wall-clock
                // second would otherwise be byte-identical. That collided with the refresh-rotation
                // fix: a "new" refresh token could equal the one we just blacklisted, handing the
                // client a token that's already revoked. A random jti guarantees every token is
                // unique regardless of timing.
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .claim("type", "refresh")
                .signWith(getSigningKey(), Jwts.SIG.HS512)
                .compact();
    }

    /** Access-token lifetime in seconds, for the {@code expiresIn} field of auth responses. */
    public long getAccessTokenExpirySeconds() {
        return jwtExpirationInMs / 1000;
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token)
                .map(Claims::getSubject)
                .orElseThrow(() -> new InvalidTokenException("Invalid JWT token"));
    }

    /**
     * Parse and verify a token once, returning its claims. Returns empty (and logs the reason) for
     * any invalid/expired/malformed token, so callers can avoid re-parsing the same token multiple
     * times in a single request (BE-M3).
     */
    public Optional<Claims> parseClaims(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (SecurityException ex) {
            log.error("Invalid JWT signature");
        } catch (MalformedJwtException ex) {
            log.error("Invalid JWT token");
        } catch (ExpiredJwtException ex) {
            log.error("Expired JWT token");
        } catch (UnsupportedJwtException ex) {
            log.error("Unsupported JWT token");
        } catch (IllegalArgumentException ex) {
            log.error("JWT claims string is empty");
        }
        return Optional.empty();
    }

    public boolean validateToken(String authToken) {
        return parseClaims(authToken).isPresent();
    }

    public boolean isRefreshToken(String token) {
        return parseClaims(token)
                .map(claims -> "refresh".equals(claims.get("type")))
                .orElse(false);
    }
}
