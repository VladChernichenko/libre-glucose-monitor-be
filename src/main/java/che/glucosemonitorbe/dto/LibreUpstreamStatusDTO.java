package che.glucosemonitorbe.dto;

/**
 * Whether LibreLinkUp is currently answering for a user, from the per-user upstream circuit breaker,
 * and whether the newest stored reading is recent enough to present as current. Either
 * {@code available == false} or {@code readingsStale == true} means the client must not present the
 * stored readings (or a forecast built on them) as current. They are separate causes: LibreLinkUp can
 * answer normally and still have nothing new when the sensor or the phone's Libre app stops uploading.
 *
 * @param available       true when the breaker is CLOSED and the last LibreLinkUp call succeeded
 * @param state           breaker state: CLOSED, OPEN or HALF_OPEN
 * @param retryInSeconds  seconds until an OPEN breaker allows the next reconnect attempt; null otherwise
 * @param lastReadingAt   epoch milliseconds of the newest stored reading; null when there is none
 * @param readingsStale   true when there is no stored reading or the newest is older than
 *                        {@code CgmFreshnessService.MAX_AGE} (the same limit dose suggestions use);
 *                        null when not computed
 */
public record LibreUpstreamStatusDTO(
        boolean available,
        String state,
        Long retryInSeconds,
        Long lastReadingAt,
        Boolean readingsStale
) {
    /** Breaker state only; the reading fields are filled in by {@link #withNewestReading}. */
    public LibreUpstreamStatusDTO(boolean available, String state, Long retryInSeconds) {
        this(available, state, retryInSeconds, null, null);
    }

    /** This status with the newest reading's time and staleness. */
    public LibreUpstreamStatusDTO withNewestReading(Long lastReadingAt, boolean readingsStale) {
        return new LibreUpstreamStatusDTO(available, state, retryInSeconds, lastReadingAt, readingsStale);
    }
}
