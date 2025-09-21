package che.glucosemonitorbe.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service to manage blacklisted JWT tokens
 * Uses in-memory storage for simplicity - in production, consider using Redis or database
 */
@Service
@Slf4j
public class TokenBlacklistService {
    
    private final Set<String> blacklistedTokens = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    public TokenBlacklistService() {
        // Clean up expired tokens every hour
        scheduler.scheduleAtFixedRate(this::cleanupExpiredTokens, 1, 1, TimeUnit.HOURS);
    }
    
    /**
     * Add a token to the blacklist
     */
    public void blacklistToken(String token) {
        if (token != null && !token.trim().isEmpty()) {
            blacklistedTokens.add(token);
            log.debug("Token blacklisted: {}", token.substring(0, Math.min(20, token.length())) + "...");
        }
    }
    
    /**
     * Check if a token is blacklisted
     */
    public boolean isTokenBlacklisted(String token) {
        return token != null && blacklistedTokens.contains(token);
    }
    
    /**
     * Remove a token from the blacklist (useful for testing)
     */
    public void removeFromBlacklist(String token) {
        if (token != null) {
            blacklistedTokens.remove(token);
            log.debug("Token removed from blacklist: {}", token.substring(0, Math.min(20, token.length())) + "...");
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
        blacklistedTokens.clear();
        log.info("Token blacklist cleared");
    }
    
    /**
     * Clean up expired tokens from blacklist
     * Note: This is a simple implementation. In production, you'd want to store
     * token expiration times and remove only truly expired tokens
     */
    private void cleanupExpiredTokens() {
        int sizeBefore = blacklistedTokens.size();
        // For now, we'll keep tokens for 24 hours after blacklisting
        // In a real implementation, you'd parse JWT expiration times
        
        // This is a placeholder - in production you'd implement proper cleanup
        // based on JWT expiration times
        log.debug("Token blacklist cleanup completed. Size: {} tokens", sizeBefore);
    }
}
