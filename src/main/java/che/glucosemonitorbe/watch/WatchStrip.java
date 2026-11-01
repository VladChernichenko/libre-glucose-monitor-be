package che.glucosemonitorbe.watch;

import java.time.LocalDateTime;

/**
 * The dashboard watch strip: what the user should look at right now. All glucose in mmol/L, all
 * times in the user's wall time (as the forecast's own timestamps). A null strip means "no strip".
 *
 * @param low        the forecast low the strip is about, when it is about one
 * @param landing    the forecast at the end of the horizon (+240 min), CALM only
 * @param nextCheckAt when the strip should be looked at again
 */
public record WatchStrip(State state, Reason reason, Suggestion suggestion,
                         GlucosePoint low, GlucosePoint landing, Double iob, Double cob,
                         LocalDateTime nextCheckAt, ActivityEstimate activityEstimate) {

    /** Priority: URGENT_LOW &gt; URGENT_HIGH &gt; CALM. */
    public enum State { CALM, URGENT_LOW, URGENT_HIGH }

    public enum Reason { LOW_NOW, TREATING_LOW, FORECAST_LOW, CARBS_LANDING,
                         HIGH_FORECAST, INSULIN_LANDING, SMALL_RISE, WATCHING, DIP_AHEAD }

    public enum Kind { CARBS, INSULIN, NO_ACTION, REFUSED }

    public enum ActivityEstimate { LEARNED, DEFAULT }

    public record Suggestion(Kind kind, Integer grams, Double units, String refusalCode,
                             Double targetMmol, Double isf, Double minWithCarbs) {}

    public record GlucosePoint(LocalDateTime at, double mmol) {}
}
