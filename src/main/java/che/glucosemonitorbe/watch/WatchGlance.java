package che.glucosemonitorbe.watch;

/**
 * Everything the Apple Watch shows, in one response: the newest reading, why it is stale (if it is),
 * the 2 h prediction and the dashboard's watch strip. The Swift {@code WatchGlance} in the iOS repo's
 * {@code Shared/} folder decodes exactly these field names.
 *
 * @param reading               newest stored reading, whatever its age; null when the user has none
 * @param staleCause            LibreLinkUp users only; NONE for everyone else
 * @param twoHourPredictionMmol null when there was nothing to calculate or the calculation failed
 * @param watch                 the dashboard watch strip, null when the dashboard shows none
 */
public record WatchGlance(Reading reading, StaleCause staleCause, Double twoHourPredictionMmol, WatchStrip watch) {

    /**
     * @param mmol      sgv / 18.018, the phone's conversion
     * @param at        epoch milliseconds
     * @param trend     LibreLinkUp TrendArrow 1-5, as stored
     * @param direction Nightscout direction, as stored
     * @param libre     the user reads from LibreLinkUp (trend is then read from {@code trend})
     */
    public record Reading(double mmol, long at, Integer trend, String direction, boolean libre) {}

    public enum StaleCause { NONE, UPSTREAM_DOWN, NO_NEW_READINGS }

    static WatchGlance empty() {
        return new WatchGlance(null, StaleCause.NONE, null, null);
    }
}
