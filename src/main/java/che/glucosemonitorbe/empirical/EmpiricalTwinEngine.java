package che.glucosemonitorbe.empirical;

import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine.Reading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * The empirical twin: a small, explainable forecasting model that steps a single glucose value
 * every 5 min for 240 min from the CGM value at the anchor, adding the increment of every logged
 * effect over each step.
 *
 * <p>A port of {@code specs/twin-port/twin-reference.js} (model v2.4), which stays the oracle:
 * {@code EmpiricalTwinGoldenTest} holds this class to it within 0.5 mg/dL. Pure - no Spring, no DB.
 * Glucose crosses the boundary in mmol/L like the rest of the backend; internally the model works
 * in mg/dL with its published constants.</p>
 *
 * <h3>Known limits (by design of the model, not of the port)</h3>
 * <ul>
 *   <li>Basal is assumed to hold glucose flat; dawn rise and afternoon creep are unmodelled.</li>
 *   <li>Heat exposure is logged but not modelled.</li>
 * </ul>
 */
public final class EmpiricalTwinEngine {

    /** mg/dL per mmol/L used by this model at its mmol/L boundary. */
    public static final double MGDL_PER_MMOL = 18.016;

    /** Forecast step [min]. */
    public static final int STEP_MIN = 5;
    /** Forecast horizon [min]. */
    public static final int HORIZON_MIN = 240;

    /** Events count if logged at or before anchor + this: a dose and the meal it covers are one decision. */
    private static final double KNOWN_AHEAD_MIN = 20;
    /** CGM gaps longer than this have no interpolated value [min]. */
    private static final double MAX_GAP_MIN = 50;
    /** An anchor past the last reading may start from it if no older than this [min]. */
    private static final double MAX_STALE_MIN = 45;
    /** Reference glycemic index: food. */
    private static final double GI_REF_MEAL = 55;
    /** Reference glycemic index: drinks. */
    private static final double GI_REF_FAST = 65;
    /** Hit band: within this many mg/dL ... */
    private static final double HIT_ABS_MGDL = 20;
    /** ... or this fraction of the actual value, whichever is larger. */
    private static final double HIT_REL = 0.2;

    private EmpiricalTwinEngine() {}

    /** One forecast point. */
    public record Point(int minute, double mmol) {}

    /**
     * 4-hour forecast from {@code anchorEpochMs} in 5-min steps.
     *
     * @return points at 0, 5, ... 240 min; empty when there is no CGM value to start from
     */
    public static List<Point> forecast(long anchorEpochMs, List<Reading> readings,
                                       List<EmpiricalEvent> events, EmpiricalTwinParams p) {
        return forecast(anchorEpochMs, readings, events, p, KNOWN_AHEAD_MIN);
    }

    /**
     * As {@link #forecast(long, List, List, EmpiricalTwinParams)}, counting events logged up to
     * {@code knownAheadMin} after the anchor. The 20 min default models a retrospective check (only
     * what was known at the time); a live what-if forecast passes {@link #HORIZON_MIN} so planned
     * meals and doses ahead of now shape the path, as they do the Hovorka one.
     */
    public static List<Point> forecast(long anchorEpochMs, List<Reading> readings,
                                       List<EmpiricalEvent> events, EmpiricalTwinParams p,
                                       double knownAheadMin) {
        List<Reading> cgm = sorted(readings);
        double t0 = minutes(anchorEpochMs);
        Double start = startMgdl(cgm, t0);
        if (start == null) return List.of();

        List<EmpiricalEvent> known = new ArrayList<>();
        List<EmpiricalEvent> walks = new ArrayList<>();
        for (EmpiricalEvent e : events) {
            if (minutes(e.epochMs()) > t0 + knownAheadMin) continue;
            switch (e.type()) {
                case WALK -> { if (e.endEpochMs() != null) walks.add(e); }
                case INSULIN, MEAL, FAST, ALCOHOL -> known.add(e);
                case BASAL, HEAT -> { }
            }
        }
        known.sort(Comparator.comparingLong(EmpiricalEvent::epochMs));

        List<Point> out = new ArrayList<>(HORIZON_MIN / STEP_MIN + 1);
        double g = start;
        out.add(new Point(0, g / MGDL_PER_MMOL));
        for (int dt = STEP_MIN; dt <= HORIZON_MIN; dt += STEP_MIN) {
            double tA = t0 + dt - STEP_MIN, tB = t0 + dt, mid = tA + STEP_MIN / 2.0;
            // insulin-sensitivity multiplier during/after walks (walkBoost = 1 -> off)
            double sens = 1 + (p.walkBoost() - 1) * walkFactor(mid, walks, p.walkTail());

            g += p.drift() * STEP_MIN / 60.0;
            // muscles using glucose directly while walking, fading over walkDropTail h afterwards
            g -= p.walkDrop() * walkFactor(mid, walks, p.walkDropTail()) * STEP_MIN / 60.0;

            for (EmpiricalEvent e : known) {
                double te = minutes(e.epochMs());
                double xA = Math.max(0, tA - te), xB = tB - te;
                if (xB <= 0) continue;
                switch (e.type()) {
                    case INSULIN -> {
                        double units = e.amount() <= 1 ? e.amount() * p.oneU() : e.amount();
                        g -= p.isf() * units * (insAct(xB, p) - insAct(xA, p)) * sens;
                    }
                    case MEAL -> {
                        double slow = Boolean.TRUE.equals(e.fat()) ? p.fatSlow() : 0;
                        double gr = giRatio(GI_REF_MEAL, e.gi());
                        double k = (Boolean.TRUE.equals(e.fruit()) ? p.kFruit() : p.kMeal()) * Math.pow(gr, p.giPow());
                        double cr = e.localHour() >= 5 && e.localHour() < 11 ? p.crAM() : p.crDay();
                        g += (p.isf() / cr) * e.amount() * Math.pow(1 / gr, p.giMag())
                                * ((1 - slow) * (carbAbs(xB, k) - carbAbs(xA, k))
                                + slow * (carbAbs(xB, p.kSlow()) - carbAbs(xA, p.kSlow())));
                    }
                    case FAST -> {
                        double gr = giRatio(GI_REF_FAST, e.gi());
                        double k = p.kFast() * Math.pow(gr, p.giPow());
                        g += p.csf() * e.amount() * Math.pow(1 / gr, p.giMag()) * (carbAbs(xB, k) - carbAbs(xA, k));
                    }
                    case ALCOHOL -> g -= p.alcDrop() * e.amount() * (alcCurve(xB) - alcCurve(xA));
                    default -> { }
                }
            }
            out.add(new Point(dt, g / MGDL_PER_MMOL));
        }
        return out;
    }

    /**
     * CGM value at {@code epochMs} by linear interpolation [mmol/L]; {@code null} outside the
     * readings or inside a gap longer than 50 min.
     */
    public static Double actualAt(List<Reading> readings, long epochMs) {
        Double mgdl = interpolateMgdl(sorted(readings), minutes(epochMs));
        return mgdl == null ? null : mgdl / MGDL_PER_MMOL;
    }

    /**
     * Evaluation anchors: every distinct logged time of an insulin, meal or fast-sugar event,
     * ascending.
     */
    public static List<Long> anchors(List<EmpiricalEvent> events) {
        TreeSet<Long> out = new TreeSet<>();
        for (EmpiricalEvent e : events) {
            switch (e.type()) {
                case INSULIN, MEAL, FAST -> out.add(e.epochMs());
                default -> { }
            }
        }
        return List.copyOf(out);
    }

    /** A check is a hit when the forecast is within max(20 mg/dL, 20% of actual) of the CGM value. */
    public static boolean isHit(double predictedMmol, double actualMmol) {
        double actualMgdl = actualMmol * MGDL_PER_MMOL;
        return Math.abs(predictedMmol - actualMmol) * MGDL_PER_MMOL <= Math.max(HIT_ABS_MGDL, HIT_REL * actualMgdl);
    }

    // -- Curves ------------------------------------------------------------------

    /** Fraction of a rapid-insulin dose that has acted by {@code x} min (exponential IOB curve). */
    private static double insAct(double x, EmpiricalTwinParams p) {
        return 1 - iobFraction(x, p.dia(), p.peak());
    }

    private static double iobFraction(double t, double td, double peak) {
        if (t <= 0) return 1;
        if (t >= td) return 0;
        double tp = Math.min(peak, td / 2 - 1);
        double tau = tp * (1 - tp / td) / (1 - 2 * tp / td);
        double a = 2 * tau / td;
        double s = 1 / (1 - a + (1 + a) * Math.exp(-td / tau));
        return 1 - s * (1 - a) * ((t * t / (tau * td * (1 - a)) - t / tau - 1) * Math.exp(-t / tau) + 1);
    }

    /** Gamma(2) carb absorption: fraction absorbed by {@code x} min, absorption rate peaking at {@code k}. */
    private static double carbAbs(double x, double k) {
        return x <= 0 ? 0 : 1 - (1 + x / k) * Math.exp(-x / k);
    }

    /** Alcohol's liver block, spread linearly from +2 h to +10 h. */
    private static double alcCurve(double x) {
        return Math.min(1, Math.max(0, (x - 120) / 480));
    }

    private static double giRatio(double reference, Double gi) {
        return gi == null || gi == 0 ? 1 : reference / gi;
    }

    /** 1 while any started walk is under way, fading linearly to 0 over {@code tailHours} after it ends. */
    private static double walkFactor(double t, List<EmpiricalEvent> walks, double tailHours) {
        double factor = 0;
        for (EmpiricalEvent w : walks) {
            if (minutes(w.epochMs()) > t) continue;
            double end = minutes(w.endEpochMs());
            double f = t <= end ? 1 : Math.max(0, 1 - (t - end) / 60 / Math.max(0.01, tailHours));
            factor = Math.max(factor, f);
        }
        return factor;
    }

    // -- CGM ---------------------------------------------------------------------

    private static Double startMgdl(List<Reading> cgm, double t0) {
        Double g0 = interpolateMgdl(cgm, t0);
        if (g0 != null || cgm.isEmpty()) return g0;
        Reading last = cgm.get(cgm.size() - 1);
        double lastT = minutes(last.epochMs());
        return t0 > lastT && t0 - lastT <= MAX_STALE_MIN ? last.mmol() * MGDL_PER_MMOL : null;
    }

    private static Double interpolateMgdl(List<Reading> cgm, double t) {
        if (cgm.isEmpty()) return null;
        if (t < minutes(cgm.get(0).epochMs()) || t > minutes(cgm.get(cgm.size() - 1).epochMs())) return null;
        for (int i = 0; i < cgm.size() - 1; i++) {
            double ta = minutes(cgm.get(i).epochMs()), tb = minutes(cgm.get(i + 1).epochMs());
            if (t < ta || t > tb) continue;
            if (tb - ta > MAX_GAP_MIN) return null;
            double ga = cgm.get(i).mmol() * MGDL_PER_MMOL, gb = cgm.get(i + 1).mmol() * MGDL_PER_MMOL;
            return tb == ta ? ga : ga + (gb - ga) * (t - ta) / (tb - ta);
        }
        return cgm.size() == 1 ? cgm.get(0).mmol() * MGDL_PER_MMOL : null;
    }

    private static List<Reading> sorted(List<Reading> readings) {
        List<Reading> cgm = new ArrayList<>(readings);
        cgm.sort(Comparator.comparingLong(Reading::epochMs));
        return cgm;
    }

    private static double minutes(long epochMs) {
        return epochMs / 60_000.0;
    }
}
