package che.glucosemonitorbe.dto;

/**
 * Whether LibreLinkUp is currently answering for a user, from the per-user upstream circuit breaker.
 * When {@code available} is false the stored readings are stale and the client should not present
 * them (or a forecast built on them) as current.
 *
 * @param available      true when the breaker is CLOSED and the last LibreLinkUp call succeeded
 * @param state          breaker state: CLOSED, OPEN or HALF_OPEN
 * @param retryInSeconds seconds until an OPEN breaker allows the next reconnect attempt; null otherwise
 */
public record LibreUpstreamStatusDTO(
        boolean available,
        String state,
        Long retryInSeconds
) {}
