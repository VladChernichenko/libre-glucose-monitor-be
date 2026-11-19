package che.glucosemonitorbe.sync;

import java.time.Instant;

/**
 * What a sync attempt reports back to its caller (a queue lane or the on-demand endpoint).
 * The caller, not the sync, decides {@code next_poll_at}.
 *
 * @param newestTimestamp newest reading epoch millis stored; only set for {@link SyncOutcome#NEW_DATA}
 * @param retryAt         earliest time the upstream accepts a call again; only set for {@link SyncOutcome#UNAVAILABLE}, may be null
 */
public record SyncResult(SyncOutcome outcome, Long newestTimestamp, Instant retryAt) {

    public static SyncResult of(SyncOutcome outcome) {
        return new SyncResult(outcome, null, null);
    }

    public static SyncResult newData(long newestTs) {
        return new SyncResult(SyncOutcome.NEW_DATA, newestTs, null);
    }

    public static SyncResult unavailable(Instant retryAtOrNull) {
        return new SyncResult(SyncOutcome.UNAVAILABLE, null, retryAtOrNull);
    }
}
