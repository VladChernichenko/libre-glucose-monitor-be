package che.glucosemonitorbe.service.libre;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LibreLinkUp {@code TrendArrow} codes (1-5). Not Dexcom/Nightscout 1-7.
 *
 * @see <a href="https://gist.github.com/khskekec/6c13ba01b10d3018d816706a32ae8ab2">LLU API dumps</a>
 */
public final class LibreLinkUpTrend {

    private LibreLinkUpTrend() {}

    /** Nightscout {@code direction} string for chart-data / CGM storage. */
    public static String toNightscoutDirection(int trend) {
        return switch (trend) {
            case 1 -> "DoubleDown";
            case 2 -> "FortyFiveDown";
            case 3 -> "Flat";
            case 4 -> "FortyFiveUp";
            case 5 -> "DoubleUp";
            default -> "Flat";
        };
    }

    /** Unicode arrow for API responses and live LLU reads. */
    public static String toArrow(int trend) {
        return switch (trend) {
            case 1 -> "\u2193\u2193";
            case 2 -> "\u2198";
            case 3 -> "\u2192";
            case 4 -> "\u2197";
            case 5 -> "\u2191\u2191";
            default -> "\u2192";
        };
    }

    /** Reads {@code TrendArrow} from an LLU graph point or live measurement node. */
    public static int readTrendCode(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.has("TrendArrow")) {
            return node.get("TrendArrow").asInt();
        }
        if (node.has("trendArrow")) {
            return node.get("trendArrow").asInt();
        }
        if (node.has("Trend")) {
            return node.get("Trend").asInt();
        }
        if (node.has("trend")) {
            return node.get("trend").asInt();
        }
        return 0;
    }
}
