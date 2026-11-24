package che.glucosemonitorbe.watch;

/**
 * The iPhone app's trend tables, so a request built for the Apple Watch carries exactly the trend the
 * phone would send for the same reading. The dashboard's alert holds are per user and shared by every
 * caller, so phone and watch must never ask with two different trends.
 *
 * <p>Must stay identical to the Swift {@code TrendArrow} in the iOS repo's {@code Shared/} folder
 * (formerly {@code BackendAPI.NightscoutEntry.trendArrow} and {@code BackendAPI.trendArrowToVelocity}).</p>
 */
public final class PhoneTrendParity {

    private PhoneTrendParity() {}

    /**
     * @param trend      LibreLinkUp {@code TrendArrow} 1-5 (used when {@code libreEntry})
     * @param direction  Nightscout direction (used otherwise)
     * @param libreEntry true for readings written by the LibreLinkUp sync
     */
    public static String arrow(Integer trend, String direction, boolean libreEntry) {
        if (libreEntry) {
            if (trend == null) return "?";
            return switch (trend) {
                case 1 -> "↓↓";
                case 2 -> "↘";
                case 3 -> "→";
                case 4 -> "↗";
                case 5 -> "↑↑";
                default -> "?";
            };
        }
        String d = direction == null ? "" : direction.trim();
        return switch (d) {
            case "DoubleUp" -> "↑↑";
            case "SingleUp" -> "↑";
            case "FortyFiveUp" -> "↗";
            case "FortyFiveDown" -> "↘";
            case "SingleDown" -> "↓";
            case "DoubleDown" -> "↓↓";
            default -> "→";
        };
    }

    /** The phone's arrow-to-velocity table [mmol/L per minute]; unknown arrows are flat. */
    public static double velocityMmolPerMin(String arrow) {
        if (arrow == null) return 0.0;
        return switch (arrow) {
            case "↑↑" -> 0.100;
            case "↑" -> 0.067;
            case "↗" -> 0.033;
            case "↘" -> -0.033;
            case "↓" -> -0.067;
            case "↓↓" -> -0.100;
            default -> 0.0;
        };
    }
}
