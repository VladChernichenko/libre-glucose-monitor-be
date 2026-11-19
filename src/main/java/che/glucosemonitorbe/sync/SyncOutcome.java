package che.glucosemonitorbe.sync;

/** Result category of one per-user glucose sync attempt. */
public enum SyncOutcome {
    /** New readings were fetched and stored. */
    NEW_DATA,
    /** Sync ran but no newer readings than last time (or none returned). */
    NO_CHANGE,
    /** No usable credentials / patientId stored for the user. */
    SKIPPED_NO_CREDS,
    /** Another sync for this user is already running and could not be coalesced in time. */
    IN_PROGRESS,
    /** Sync failed (auth/network/decrypt error). */
    ERROR,
    /** The upstream circuit is open and the call was not attempted. */
    UNAVAILABLE,
    /** The upstream answered HTTP 429. */
    RATE_LIMITED
}
