package che.glucosemonitorbe.dto;

import java.io.Serializable;

/**
 * Lightweight, cache-friendly snapshot of a user's active Nightscout config.
 * A record (immutable, Serializable) is safe to cache across threads without the
 * lazy-loading pitfalls of a JPA entity.
 */
public record NightscoutCredentials(String url, String apiSecret, String apiToken) implements Serializable {
}
