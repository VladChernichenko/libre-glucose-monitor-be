package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.RevokedToken;
import che.glucosemonitorbe.repository.TokenBlacklistRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Manages revoked JWTs, backed by the {@code token_blacklist} table (BE-H3).
 *
 * <p>Durable across restarts and shared across instances (the previous in-memory map lost all
 * revocations on restart and was per-instance). Tokens are stored as a SHA-256 hex hash — the raw
 * token is never persisted. Naturally-expired entries are pruned hourly and ignored on lookup.
 */
@Service
@Slf4j
public class TokenBlacklistService {

    /** Sentinel prefix for logout-all-devices (not a JWT). */
    public static final String LOGOUT_ALL_DEVICES_PREFIX = "LOGOUT_ALL_DEVICES:";

    private static final long FALLBACK_TTL_MS = TimeUnit.HOURS.toMillis(24);
    /** Extra buffer so the sentinel outlives clock skew past the last refresh token. */
    private static final long LOGOUT_ALL_TTL_BUFFER_MS = TimeUnit.HOURS.toMillis(1);

    private final TokenBlacklistRepository repository;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    /** Must cover the full refresh-token lifetime so stolen refresh tokens cannot revive after logout-all. */
    @Value("${security.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    public TokenBlacklistService(TokenBlacklistRepository repository) {
        this.repository = repository;
    }

    private long logoutAllTtlMs() {
        return Math.max(refreshExpirationMs, FALLBACK_TTL_MS) + LOGOUT_ALL_TTL_BUFFER_MS;
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    /** Extract a token's expiry (ms epoch), tolerating already-expired tokens; null if unparseable. */
    private Long extractExpirationTime(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Date expiration = claims.getExpiration();
            return expiration != null ? expiration.getTime() : null;
        } catch (ExpiredJwtException e) {
            Date expiration = e.getClaims().getExpiration();
            return expiration != null ? expiration.getTime() : null;
        } catch (Exception e) {
            log.warn("Failed to parse JWT token expiration: {}", e.getMessage());
            return null;
        }
    }

    /** Invalidate all sessions for a user until re-login (logout-all-devices). */
    @Transactional
    public void blacklistAllDevicesForUser(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        long expiration = System.currentTimeMillis() + logoutAllTtlMs();
        store(LOGOUT_ALL_DEVICES_PREFIX + username.trim(), expiration);
        log.info("Global logout sentinel registered for user {} until {}", username, new Date(expiration));
    }

    /**
     * Clears the logout-all sentinel so a successful password login can issue usable tokens again.
     */
    @Transactional
    public void clearGlobalLogout(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        repository.deleteById(sha256Hex(LOGOUT_ALL_DEVICES_PREFIX + username.trim()));
        log.info("Global logout sentinel cleared for user {}", username);
    }

    @Transactional(readOnly = true)
    public boolean isUserGloballyLoggedOut(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return isActive(LOGOUT_ALL_DEVICES_PREFIX + username.trim());
    }

    @Transactional
    public void blacklistToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        if (token.startsWith(LOGOUT_ALL_DEVICES_PREFIX)) {
            store(token, System.currentTimeMillis() + logoutAllTtlMs());
            return;
        }
        Long expirationTime = extractExpirationTime(token);
        if (expirationTime == null) {
            expirationTime = System.currentTimeMillis() + FALLBACK_TTL_MS;
            log.warn("Token blacklisted with fallback expiration (24h)");
        }
        store(token, expirationTime);
        log.debug("Token blacklisted until {}", new Date(expirationTime));
    }

    @Transactional(readOnly = true)
    public boolean isTokenBlacklisted(String token) {
        return token != null && isActive(token);
    }

    /**
     * Atomically blacklists {@code token} iff it isn't already blacklisted, returning {@code true}
     * iff this call performed the insert. Two concurrent calls with the same token race on a single
     * DB-level {@code INSERT ... ON CONFLICT DO NOTHING}: exactly one gets {@code true} (the
     * "winner"), the other gets {@code false}. This is the race-breaker for refresh-token rotation —
     * see {@link AuthService#refreshToken}, which must reject the loser instead of also minting a
     * second valid token pair from the same refresh token.
     */
    @Transactional
    public boolean blacklistTokenIfAbsent(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        Long expirationTime = extractExpirationTime(token);
        if (expirationTime == null) {
            expirationTime = System.currentTimeMillis() + FALLBACK_TTL_MS;
            log.warn("Token blacklisted with fallback expiration (24h)");
        }
        int inserted = repository.insertIfAbsent(sha256Hex(token), Instant.ofEpochMilli(expirationTime));
        return inserted > 0;
    }

    /** Remove a token from the blacklist (useful for testing). */
    @Transactional
    public void removeFromBlacklist(String token) {
        if (token != null) {
            repository.deleteById(sha256Hex(token));
        }
    }

    /** Number of blacklisted entries (for monitoring). */
    @Transactional(readOnly = true)
    public int getBlacklistSize() {
        return (int) repository.count();
    }

    /** Clear all blacklisted tokens (useful for testing). */
    @Transactional
    public void clearBlacklist() {
        repository.deleteAll();
    }

    /** Hourly housekeeping: drop naturally-expired entries (expired entries are ignored on lookup anyway). */
    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    @Transactional
    public void cleanupExpiredTokens() {
        int removed = repository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Token blacklist cleanup removed {} expired entries", removed);
        }
    }

    private void store(String rawKey, long expiresAtMs) {
        repository.save(new RevokedToken(sha256Hex(rawKey), Instant.ofEpochMilli(expiresAtMs)));
    }

    private boolean isActive(String rawKey) {
        return repository.existsByTokenHashAndExpiresAtAfter(sha256Hex(rawKey), Instant.now());
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
