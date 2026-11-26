package che.glucosemonitorbe.service.libre;

import che.glucosemonitorbe.domain.LibreSession;
import che.glucosemonitorbe.repository.LibreSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user LibreLinkUp session state (token, resolved base URL, account-id hash, locale, expiry).
 *
 * <p>An in-memory cache in front of the {@code libre_session} table: a login writes through, and the
 * first lookup for a user after a restart loads their row, so the backend does not log every Libre
 * user in again on startup. A token within {@link #EXPIRY_MARGIN} of its expiry reads as absent, which
 * makes the caller log in again before LibreLinkUp starts answering 401. If the database is down, the
 * store degrades to memory only (the behaviour before persistence).
 */
@Component
public class LibreLinkUpSessionStore {

    private static final Logger log = LoggerFactory.getLogger(LibreLinkUpSessionStore.class);

    /** EU default Accept-Language when the user has no stored locale. */
    public static final String DEFAULT_LOCALE = "en-GB";
    /** Log in again this long before the token's stated expiry. */
    public static final Duration EXPIRY_MARGIN = Duration.ofHours(24);

    /** One user's session; any field but the token may be null. */
    public record Session(String token, String baseUrl, String accountId, String locale, Instant expiresAt) {
    }

    private final LibreSessionRepository repository;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Session> cache = new ConcurrentHashMap<>();
    /** Users whose DB row has been read (or written/deleted) by this instance: no further DB reads. */
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    @Autowired
    public LibreLinkUpSessionStore(LibreSessionRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public LibreLinkUpSessionStore(LibreSessionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Token for userId, or null if there is none or it is (about to be) expired. */
    public String token(UUID userId) {
        Session s = session(userId);
        if (s == null || s.token() == null) {
            return null;
        }
        if (s.expiresAt() != null && !clock.instant().isBefore(s.expiresAt().minus(EXPIRY_MARGIN))) {
            log.info("LibreLinkUp token for user {} expires at {} - treating as absent so it is renewed",
                    userId, s.expiresAt());
            return null;
        }
        return s.token();
    }

    /** SHA-256(user.id) stored after login - required as the account-id header (Nov 2024+). */
    public String accountId(UUID userId) {
        Session s = session(userId);
        return s != null ? s.accountId() : null;
    }

    /** Resolved base URL for userId, or {@code normalizedDefault} when none is stored. */
    public String baseUrlOrDefault(UUID userId, String normalizedDefault) {
        Session s = session(userId);
        return s != null && s.baseUrl() != null ? s.baseUrl() : normalizedDefault;
    }

    /** Accept-Language for userId; falls back to {@link #DEFAULT_LOCALE}. */
    public String localeOrDefault(UUID userId) {
        Session s = session(userId);
        return s != null && s.locale() != null && !s.locale().isBlank() ? s.locale() : DEFAULT_LOCALE;
    }

    public boolean isAuthenticated(UUID userId) {
        return token(userId) != null;
    }

    /**
     * Records a successful login. Null {@code baseUrl}/{@code accountId}/{@code locale} keep the
     * previously stored value (a login on the default host does not unpin a region).
     */
    public void saveLogin(UUID userId, String token, Instant expiresAt, String locale, String baseUrl, String accountId) {
        if (userId == null || token == null) {
            return;
        }
        Session prev = session(userId);
        Session next = new Session(token,
                baseUrl != null ? baseUrl : prev != null ? prev.baseUrl() : null,
                accountId != null ? accountId : prev != null ? prev.accountId() : null,
                locale != null && !locale.isBlank() ? locale : prev != null ? prev.locale() : null,
                expiresAt);
        cache.put(userId, next);
        loaded.add(userId);
        try {
            repository.save(new LibreSession(userId, next.token(), next.baseUrl(), next.accountId(),
                    next.locale(), next.expiresAt(), clock.instant()));
        } catch (DataAccessException e) {
            log.warn("Could not persist LibreLinkUp session for user {} (kept in memory): {}", userId, e.getMessage());
        }
    }

    /** Remove all session state for a user (logout, rejected token, changed credentials). */
    public void clear(UUID userId) {
        if (userId == null) {
            return;
        }
        cache.remove(userId);
        loaded.add(userId);
        try {
            if (repository.existsById(userId)) {
                repository.deleteById(userId);
            }
        } catch (DataAccessException e) {
            // Not marked loaded-and-absent in the DB sense: drop the flag so a later read retries.
            loaded.remove(userId);
            log.warn("Could not delete persisted LibreLinkUp session for user {}: {}", userId, e.getMessage());
        }
    }

    private Session session(UUID userId) {
        if (userId == null) {
            return null;
        }
        Session s = cache.get(userId);
        if (s != null || loaded.contains(userId)) {
            return s;
        }
        try {
            Session fromDb = repository.findById(userId)
                    .map(r -> new Session(r.getToken(), r.getBaseUrl(), r.getAccountId(), r.getLocale(), r.getExpiresAt()))
                    .orElse(null);
            if (fromDb != null) {
                Session raced = cache.putIfAbsent(userId, fromDb);
                if (raced == null) {
                    log.info("Restored persisted LibreLinkUp session for user {} (no login needed)", userId);
                }
            }
            loaded.add(userId);
            return cache.get(userId);
        } catch (RuntimeException e) {
            // DB down or the token no longer decrypts (rotated key): behave as if there were no session.
            log.warn("Could not load persisted LibreLinkUp session for user {}: {}", userId, e.getMessage());
            return null;
        }
    }
}
