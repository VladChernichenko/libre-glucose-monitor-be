package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.TwinScales;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One scenario episode reduced to what the forecast needs: times are minutes relative to the episode
 * start, the calendar is reduced to a clock hour and a weekday, glucose is rounded to 0.1 mmol/L and the
 * profile to two significant figures. That removes dates, ids and exact values, but these are still
 * <em>pseudonymised excerpts of real patient data</em>, not anonymous: with the public dataset in hand a
 * determined reader could match one back. Committing them is therefore gated on the dataset licence.
 * The twin is stored as the scales it produced, so a replay exercises the forecast, not the fit.
 * {@link #replay()} rebuilds the episode on a placeholder date and runs it, which is also how
 * {@code expected} is recorded.
 */
record ScenarioFixture(String id, ScenarioKind kind, int horizonMin, int hourOfDay, int weekday,
                       Profile profile, double isfScale, double agScale, double egpScale, boolean twinApplied,
                       List<Reading> readings, List<Event> events, Expectation expected) {

    record Profile(double weightKg, double isfMmolPerU, double carbRatioGPerU, double basalUPerH) {}
    record Reading(int minute, double mmol) {}
    record Event(int minute, double carbsG, double insulinU) {}
    record Expectation(Outcome outcome, double errorMmol) {}

    private static final long MIN = 60_000L;
    private static final int START_TOLERANCE_MIN = 5;
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern SUBJECT = Pattern.compile("(?i)subject");
    /** An integer of nine or more digits is an epoch timestamp (seconds or milliseconds), not a measurement. */
    private static final Pattern EPOCH = Pattern.compile("(?<![\\d.])\\d{9,}(?![\\d.])");

    /** Why a fixture's text could identify a person; empty when it cannot. */
    static List<String> privacyViolations(String json) {
        List<String> out = new ArrayList<>();
        if (DATE.matcher(json).find()) out.add("contains a calendar date");
        if (SUBJECT.matcher(json).find()) out.add("mentions a subject");
        if (EPOCH.matcher(json).find()) out.add("contains an epoch timestamp");
        return out;
    }

    ScenarioFixture withScales(double isf, double ag, double egp) {
        return new ScenarioFixture(id, kind, horizonMin, hourOfDay, weekday, profile, isf, ag, egp, true,
                readings, events, expected);
    }

    ScenarioFixture withExpected(Expectation e) {
        return new ScenarioFixture(id, kind, horizonMin, hourOfDay, weekday, profile, isfScale, agScale, egpScale,
                twinApplied, readings, events, e);
    }

    /** The forecast for this episode, with the stored twin scales, on a placeholder date matching the clock hour and weekday. */
    ForecastResult replay() {
        long start = LocalDateTime.of(2001, 1, 1, hourOfDay, 0).plusDays(weekday - 1L)
                .toInstant(ZoneOffset.UTC).toEpochMilli(); // 2001-01-01 was a Monday
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (Reading r : readings) {
            cgm.add(new PredictionReplayEngine.Reading(start + r.minute() * MIN, r.mmol()));
        }
        // The reading nearest the start, as the engine aligns an anchor; a fixture without one cannot be judged.
        double startMmol = readings.stream()
                .filter(r -> Math.abs(r.minute()) <= START_TOLERANCE_MIN)
                .min(java.util.Comparator.comparingInt((Reading r) -> Math.abs(r.minute())).thenComparingInt(Reading::minute))
                .map(Reading::mmol)
                .orElseThrow(() -> new IllegalStateException(
                        id + " has no reading within " + START_TOLERANCE_MIN + " minutes of the episode start"));
        List<PredictionReplayEngine.Event> evs = new ArrayList<>();
        for (Event e : events) {
            evs.add(new PredictionReplayEngine.Event(start + e.minute() * MIN, e.carbsG(), e.insulinU(),
                    false, 0.0, 0.0, 0.0));
        }
        Azt1dDataset.Profile p = new Azt1dDataset.Profile(0, 0, profile.basalUPerH(), profile.carbRatioGPerU(),
                profile.isfMmolPerU(), profile.weightKg(), true,
                profile.isfMmolPerU(), profile.isfMmolPerU(), profile.isfMmolPerU());
        Azt1dDataset.Subject subject = new Azt1dDataset.Subject("fixture", cgm, evs, p);
        ScenarioEpisode ep = new ScenarioEpisode("fixture", kind, start, startMmol, horizonMin);
        return new ScenarioForecaster(subject).forecastWith(ep, new TwinScales(isfScale, agScale, 1.0, egpScale));
    }
}
