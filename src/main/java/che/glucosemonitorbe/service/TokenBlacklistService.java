package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.RevokedToken;
import che.glucosemonitorbe.entity.UserTokenWatermark;
import che.glucosemonitorbe.repository.TokenBlacklistRepository;
import che.glucosemonitorbe.repository.UserTokenWatermarkRepository;
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
 * revocations on restart and was per-instance). Tokens are stored as a SHA-256 hex hash - the raw
 * token is never persisted. Naturally-expired entries are pruned hourly and ignored on lookup.
 */
@Service
@Slf4j
public class TokenBlacklistService {

    /** Key prefix of the short-lived "this refresh token was just rotated" marker (not a JWT). */
    private static final String ROTATED_PREFIX = "ROTATED:";

    private static final long FALLBACK_TTL_MS = TimeUnit.HOURS.toMillis(24);

    /** Outcome of presenting a refresh token for rotation. */
    public enum RotationResult {
        /** First use: the token is now spent and a new pair may be minted. */
        ROTATED,
        /** Already spent, but only moments ago by a rotation - the client most likely never received
         *  (or raced) the response, so a new pair may be minted instead of signing the user out. */
        REUSED_WITHIN_GRACE,
        /** Spent earlier, or revoked by logout: reject. */
        REJECTED
    }

    private final TokenBlacklistRepository repository;
    private final UserTokenWatermarkRepository watermarkRepository;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    /**
     * How long a just-rotated refresh token may be presented again. Covers a refresh response lost
     * in transit (app suspended, network drop) and parallel refreshes; 0 disables the tolerance.
     */
    @Value("${security.jwt.refresh-reuse-grace-ms:60000}")
    private long refreshReuseGraceMs;

    public TokenBlacklistService(TokenBlacklistRepository repository,
                                 UserTokenWatermarkRepository watermarkRepository) {
        this.repository = repository;
        this.watermarkRepository = watermarkRepository;
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

    /**
     * Logout-all-devices: every token for this user issued before now is rejected from here on.
     * The watermark is never cleared - a later login simply mints tokens issued after it.
     */
    @Transactional
    public void blacklistAllDevicesForUser(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        watermarkRepository.save(new UserTokenWatermark(username.trim(), now));
        log.info("Logout-all watermark set for user {} at {}", username, now);
    }

    /**
     * True if a token issued at {@code issuedAt} for {@code username} predates the user's last
     * logout-all. A token with no issue time cannot be shown to postdate it, so it is rejected
     * whenever a watermark exists.
     */
    @Transactional(readOnly = true)
    public boolean isRevokedByLogoutAll(String username, Instant issuedAt) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return watermarkRepository.findById(username.trim())
                .map(w -> issuedAt == null || issuedAt.isBefore(w.getTokensValidAfter()))
                .orElse(false);
    }

    @Transactional
    public void blacklistToken(String token) {
        if (token == null || token.trim().isEmpty()) {
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
     * Spends a refresh token for rotation. One DB-level {@code INSERT ... ON CONFLICT DO NOTHING}
     * arbitrates concurrent callers: exactly one gets {@link RotationResult#ROTATED}. The winner also
     * writes a short-lived marker in the same transaction; a caller that loses the insert waits on
     * that transaction, then sees the marker and gets {@link RotationResult#REUSED_WITHIN_GRACE}
     * while it is fresh. A token revoked by logout has no marker and is always
     * {@link RotationResult#REJECTED}, as is any reuse after the grace period.
     */
    @Transactional
    public RotationResult rotateRefreshToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return RotationResult.REJECTED;
        }
        Long expirationTime = extractExpirationTime(token);
        if (expirationTime == null) {
            expirationTime = System.currentTimeMillis() + FALLBACK_TTL_MS;
            log.warn("Token blacklisted with fallback expiration (24h)");
        }
        int inserted = repository.insertIfAbsent(sha256Hex(token), Instant.ofEpochMilli(expirationTime));
        if (inserted > 0) {
            if (refreshReuseGraceMs > 0) {
                repository.insertIfAbsent(sha256Hex(ROTATED_PREFIX + token),
                        Instant.now().plusMillis(refreshReuseGraceMs));
            }
            return RotationResult.ROTATED;
        }
        return isActive(ROTATED_PREFIX + token) ? RotationResult.REUSED_WITHIN_GRACE : RotationResult.REJECTED;
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
