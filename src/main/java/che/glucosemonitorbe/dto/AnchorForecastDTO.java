package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The empirical twin's 4 h forecast from one logged note, beside what the CGM actually did.
 * Times are the user's local wall time; glucose is mmol/L.
 *
 * <p>The forecast is the snapshot frozen when the note was logged: every 5 min from +5, with
 * lower and upper equal to glucose (no cone) and coverage 0.</p>
 *
 * @param startGlucose CGM value at the anchor; null when there is none, and then no forecast
 * @param forecast     every 5 min from the anchor to +240, with the likely-range cone
 * @param actual       CGM readings from 60 min before the anchor to 240 min after
 * @param markers      doses, carbs, basal and activity in that window
 * @param checks       forecast vs actual at +60, +120 and +240
 * @param pending      the +240 point is still in the future
 */
public record AnchorForecastDTO(
        ForecastAnchorDTO anchor,
        Double startGlucose,
        List<Point> forecast,
        List<Reading> actual,
        List<Marker> markers,
        List<Check> checks,
        double coverage,
        boolean pending
) {
    public record Point(
            int minute,
            @JsonFormat(pattern = GlycemicEventDTO.TIME) LocalDateTime time,
            double glucose,
            double lower,
            double upper
    ) {}

    public record Reading(@JsonFormat(pattern = GlycemicEventDTO.TIME) LocalDateTime time, double glucose) {}

    /** @param kind INSULIN, BASAL, CARBS or ACTIVITY; amount is units, grams or minutes */
    public record Marker(@JsonFormat(pattern = GlycemicEventDTO.TIME) LocalDateTime time, String kind, double amount) {}

    /** @param actual null when the CGM has no value there yet (or a gap); hit then null too */
    public record Check(int minute, Double forecast, Double actual, Boolean hit) {}
}
