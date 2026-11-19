package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.AnchorSample;

import java.util.List;
import java.util.OptionalDouble;

/**
 * The pass/fail rule of each scenario kind. A rule only judges the forecast when reality met the
 * rule's precondition (the glucose really fell, really rose, really stayed put); otherwise the verdict
 * is NOT_APPLICABLE. Tolerances on the size of the error are not rules here: they come from the
 * recorded baseline.
 */
final class ScenarioRules {

    /** A correction forecast must capture at least this share of the fall that really happened. */
    static final double FLAT_FORECAST_RATIO = 0.6;
    /** A correction rule applies only when glucose really fell at least this much by 120 minutes [mmol/L]. */
    static final double MIN_REAL_FALL = 1.0;
    /** A meal rule applies only when glucose really rose at least this much [mmol/L]. */
    static final double MIN_REAL_RISE = 2.0;
    /** A meal forecast must rise at least this much [mmol/L]. */
    static final double MIN_FORECAST_RISE = 1.0;
    /** An overnight rule applies only when reality stayed within this band around the start [mmol/L]. */
    static final double OVERNIGHT_BAND = 2.0;

    private static final int CHECK_MIN = 120;

    private ScenarioRules() {}

    static Verdict evaluate(ForecastResult r) {
        if (!Double.isFinite(r.episode().startMmol())) {
            // Without a start glucose every comparison below is false and a forecast would "pass" vacuously.
            throw new IllegalArgumentException("episode has no start glucose: " + r.episode());
        }
        OptionalDouble p120 = r.predictedAt(CHECK_MIN);
        OptionalDouble a120 = r.actualAt(CHECK_MIN);
        if (p120.isEmpty() || a120.isEmpty()) {
            return new Verdict(Outcome.NOT_APPLICABLE, r.episode().kind().name(), Double.NaN,
                    "no " + CHECK_MIN + "-minute sample");
        }
        double error = Math.abs(p120.getAsDouble() - a120.getAsDouble());
        double start = r.episode().startMmol();
        return switch (r.episode().kind()) {
            case CORRECTION_DROP -> correction(start, p120.getAsDouble(), a120.getAsDouble(), error);
            case MEAL_WITH_BOLUS, MEAL_NO_BOLUS -> meal(r, start, error);
            case OVERNIGHT_STEADY -> overnight(r, start, p120.getAsDouble(), error);
        };
    }

    private static Verdict correction(double start, double predicted120, double actual120, double error) {
        double realFall = start - actual120;
        double forecastFall = start - predicted120;
        if (realFall < MIN_REAL_FALL) {
            return new Verdict(Outcome.NOT_APPLICABLE, "flat-forecast", error,
                    String.format("real fall %.1f is under %.1f", realFall, MIN_REAL_FALL));
        }
        if (forecastFall < FLAT_FORECAST_RATIO * realFall) {
            return new Verdict(Outcome.FAIL, "flat-forecast", error,
                    String.format("forecast fell %.1f, reality %.1f", forecastFall, realFall));
        }
        return new Verdict(Outcome.PASS, "flat-forecast", error,
                String.format("forecast fell %.1f, reality %.1f", forecastFall, realFall));
    }

    private static Verdict meal(ForecastResult r, double start, double error) {
        double realRise = max(r.samples(), true) - start;
        double forecastRise = max(r.samples(), false) - start;
        if (realRise < MIN_REAL_RISE) {
            return new Verdict(Outcome.NOT_APPLICABLE, "no-rise", error,
                    String.format("real rise %.1f is under %.1f", realRise, MIN_REAL_RISE));
        }
        Outcome o = forecastRise < MIN_FORECAST_RISE ? Outcome.FAIL : Outcome.PASS;
        return new Verdict(o, "no-rise", error,
                String.format("forecast rose %.1f, reality %.1f", forecastRise, realRise));
    }

    private static Verdict overnight(ForecastResult r, double start, double predicted120, double error) {
        boolean steady = r.samples().stream().allMatch(s -> Math.abs(s.actual() - start) <= OVERNIGHT_BAND);
        if (!steady) {
            return new Verdict(Outcome.NOT_APPLICABLE, "drift", error, "reality left the band");
        }
        double drift = Math.abs(predicted120 - start);
        Outcome o = drift > OVERNIGHT_BAND ? Outcome.FAIL : Outcome.PASS;
        return new Verdict(o, "drift", error, String.format("forecast drifted %.1f", drift));
    }

    private static double max(List<AnchorSample> samples, boolean actual) {
        return samples.stream().mapToDouble(s -> actual ? s.actual() : s.predicted()).max().orElse(Double.NaN);
    }
}
