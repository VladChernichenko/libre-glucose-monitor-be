package che.glucosemonitorbe.service.libre;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user LibreLinkUp session state (token, resolved base URL, account-id hash, locale).
 *
 * <p>Extracted from {@code LibreLinkUpService} (BE-M5 decomposition). State is in-memory and
 * per-instance - the same caveat as the previous inline maps. If LibreLinkUp sessions ever need to
 * survive restarts or be shared across instances, this is the single seam to back with Redis/DB
 * (mirroring the token-blacklist work in BE-H3).
 */
@Component
public class LibreLinkUpSessionStore {

    /** EU default Accept-Language when the user has no stored locale. */
    public static final String DEFAULT_LOCALE = "en-GB";

    private final ConcurrentHashMap<UUID, String> tokenStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> baseUrlStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> accountIdStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> localeStore = new ConcurrentHashMap<>();

    /** Token for userId, or null if not authenticated. */
    public String token(UUID userId) {
        return userId != null ? tokenStore.get(userId) : null;
    }

    /** SHA-256(user.id) stored after login - required as the account-id header (Nov 2024+). */
    public String accountId(UUID userId) {
        return userId != null ? accountIdStore.get(userId) : null;
    }

    /** Resolved base URL for userId, or {@code normalizedDefault} when none is stored. */
    public String baseUrlOrDefault(UUID userId, String normalizedDefault) {
        if (userId != null) {
            String stored = baseUrlStore.get(userId);
            if (stored != null) {
                return stored;
            }
        }
        return normalizedDefault;
    }

    /** Accept-Language for userId; falls back to {@link #DEFAULT_LOCALE}. */
    public String localeOrDefault(UUID userId) {
        if (userId != null) {
            String stored = localeStore.get(userId);
            if (stored != null && !stored.isBlank()) {
                return stored;
            }
        }
        return DEFAULT_LOCALE;
    }

    public void putToken(UUID userId, String token) {
        tokenStore.put(userId, token);
    }

    public void putBaseUrl(UUID userId, String baseUrl) {
        baseUrlStore.put(userId, baseUrl);
    }

    public void putAccountId(UUID userId, String accountIdHash) {
        accountIdStore.put(userId, accountIdHash);
    }

    public void putLocale(UUID userId, String locale) {
        localeStore.put(userId, locale);
    }

    public boolean isAuthenticated(UUID userId) {
        return userId != null && tokenStore.containsKey(userId);
    }

    /** Remove all session state for a user (logout). */
    public void clear(UUID userId) {
        if (userId != null) {
            tokenStore.remove(userId);
            baseUrlStore.remove(userId);
            accountIdStore.remove(userId);
            localeStore.remove(userId);
        }
    }
}
