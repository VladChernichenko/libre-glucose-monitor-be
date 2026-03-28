package che.glucosemonitorbe.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service to manage blacklisted JWT tokens
 * Uses in-memory storage with expiration tracking - in production, consider using Redis or database
 * 
 * FIXED: Properly tracks token expiration times and cleans up expired tokens to prevent memory leaks
 */
@Service
@Slf4j
public class TokenBlacklistService {
    
    // Map of token -> expiration timestamp (milliseconds)
    private final Map<String, Long> blacklistedTokens = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    @Value("${security.jwt.secret}")
    private String jwtSecret;
    
    public TokenBlacklistService() {
        // Clean up expired tokens every hour
        scheduler.scheduleAtFixedRate(this::cleanupExpiredTokens, 1, 1, TimeUnit.HOURS);
        log.info("TokenBlacklistService initialized with automatic cleanup every hour");
    }
    
    /**
     * Get signing key for JWT parsing
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }
    
    /**
     * Extract expiration time from JWT token
     */
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
            // Token is already expired, get expiration from exception
            Date expiration = e.getClaims().getExpiration();
            return expiration != null ? expiration.getTime() : null;
        } catch (Exception e) {
            log.warn("Failed to parse JWT token expiration: {}", e.getMessage());
            // Default to 24 hours from now if we can't parse
            return System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24);
        }
    }
    
    /**
     * Add a token to the blacklist with its expiration time
     */
    public void blacklistToken(String token) {
        if (token != null && !token.trim().isEmpty()) {
            Long expirationTime = extractExpirationTime(token);
            
            if (expirationTime != null) {
                blacklistedTokens.put(token, expirationTime);
                log.debug("Token blacklisted until {}: {}...", 
                        new Date(expirationTime),
                        token.substring(0, Math.min(20, token.length())));
            } else {
                // Fallback: keep for 24 hours if we can't determine expiration
                long fallbackExpiration = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24);
                blacklistedTokens.put(token, fallbackExpiration);
                log.warn("Token blacklisted with fallback expiration (24h): {}...", 
                        token.substring(0, Math.min(20, token.length())));
            }
        }
    }
    
    /**
     * Check if a token is blacklisted and not expired
     */
    public boolean isTokenBlacklisted(String token) {
        if (token == null) {
            return false;
        }
        
        Long expirationTime = blacklistedTokens.get(token);
        if (expirationTime == null) {
            return false;
        }
        
        // Check if the token is still valid (not expired)
        long currentTime = System.currentTimeMillis();
        if (currentTime > expirationTime) {
            // Token has expired, remove it from blacklist
            blacklistedTokens.remove(token);
            log.debug("Removed expired token from blacklist during lookup");
            return false;
        }
        
        return true;
    }
    
    /**
     * Remove a token from the blacklist (useful for testing)
     */
    public void removeFromBlacklist(String token) {
        if (token != null) {
            blacklistedTokens.remove(token);
            log.debug("Token removed from blacklist: {}...", 
                    token.substring(0, Math.min(20, token.length())));
        }
    }
    
    /**
     * Get the number of blacklisted tokens (for monitoring)
     */
    public int getBlacklistSize() {
        return blacklistedTokens.size();
    }
    
    /**
     * Clear all blacklisted tokens (useful for testing)
     */
    public void clearBlacklist() {
        int sizeBefore = blacklistedTokens.size();
        blacklistedTokens.clear();
        log.info("Token blacklist cleared: {} tokens removed", sizeBefore);
    }
    
    /**
     * Clean up expired tokens from blacklist
     * This prevents memory leaks by removing tokens that have naturally expired
     */
    private void cleanupExpiredTokens() {
        int sizeBefore = blacklistedTokens.size();
        long currentTime = System.currentTimeMillis();
        int removedCount = 0;
        
        // Iterate through all blacklisted tokens and remove expired ones
        Iterator<Map.Entry<String, Long>> iterator = blacklistedTokens.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            Long expirationTime = entry.getValue();
            
            if (expirationTime != null && currentTime > expirationTime) {
                iterator.remove();
                removedCount++;
                log.trace("Removed expired token from blacklist: {}...", 
                        entry.getKey().substring(0, Math.min(20, entry.getKey().length())));
            }
        }
        
        int sizeAfter = blacklistedTokens.size();
        log.info("Token blacklist cleanup completed: removed {} expired tokens, {} remaining (was {})", 
                removedCount, sizeAfter, sizeBefore);
    }
    
    /**
     * Shutdown scheduler gracefully (called by Spring on bean destruction)
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down TokenBlacklistService scheduler");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
