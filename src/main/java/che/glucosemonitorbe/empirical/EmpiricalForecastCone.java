package che.glucosemonitorbe.empirical;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The likely-range cone around an empirical-twin forecast: how far the forecast has typically been
 * off at each horizon. It starts as a single point at the anchor (the forecast starts from the CGM
 * value, so it cannot be off there) and widens as the forecast looks further ahead.
 *
 * <p>The half-width at each knot is the {@value #COVERAGE} quantile of past absolute forecast errors
 * |forecast − CGM| at that horizon, so about 90% of readings land inside the cone. Pure - no Spring,
 * no DB. Works in mg/dL like the rest of the model's constants.</p>
 *
 * <p>{@link #defaults()} were measured in the claude.ai "Vlad's Glucose Twin" artifact (model v2.5,
 * Oct 19 2026) over about 135 anchors, Oct 8-19 2026. {@link #fit(List)} re-measures them from a
 * user's own anchors.</p>
 */
public final class EmpiricalForecastCone {

    /** Share of past errors the cone covers. */
    public static final double COVERAGE = 0.9;
    /** Standard-normal z for a central {@link #COVERAGE} interval, used to report an equivalent σ. */
    public static final double Z = 1.6449;
    /** Horizon knots [min]. */
    public static final int[] KNOTS = {0, 15, 30, 60, 90, 120, 180, 240};
    /** A knot needs at least this many errors to be measured; fewer keeps the default. */
    static final int MIN_SAMPLES = 5;

    private static final double[] DEFAULT_MGDL = {0, 19, 33, 52, 58, 61, 72, 78};

    /** One past forecast error. */
    public record Sample(int horizonMin, double absErrorMgdl) {}

    private final double[] halfWidthMgdl;

    private EmpiricalForecastCone(double[] halfWidthMgdl) {
        double[] w = halfWidthMgdl.clone();
        w[0] = 0;
        for (int i = 1; i < w.length; i++) w[i] = Math.max(w[i], w[i - 1]); // never narrows
        this.halfWidthMgdl = w;
    }

    /** Cone measured on the reference data (model v2.5). */
    public static EmpiricalForecastCone defaults() {
        return new EmpiricalForecastCone(DEFAULT_MGDL);
    }

    /**
     * Measure the cone from past errors. Each sample counts at the nearest knot; knots with fewer than
     * {@value #MIN_SAMPLES} samples keep their default width.
     */
    public static EmpiricalForecastCone fit(List<Sample> samples) {
        List<List<Double>> byKnot = new ArrayList<>();
        for (int k : KNOTS) byKnot.add(new ArrayList<>());
        if (samples != null) {
            for (Sample s : samples) {
                if (s.horizonMin() <= 0 || Double.isNaN(s.absErrorMgdl())) continue;
                byKnot.get(nearestKnot(s.horizonMin())).add(Math.abs(s.absErrorMgdl()));
            }
        }
        double[] w = DEFAULT_MGDL.clone();
        for (int i = 1; i < KNOTS.length; i++) {
            List<Double> e = byKnot.get(i);
            if (e.size() < MIN_SAMPLES) continue;
            double[] a = e.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            w[i] = a[(int) Math.floor(COVERAGE * (a.length - 1))];
        }
        return new EmpiricalForecastCone(w);
    }

    /** Half-width of the cone [mg/dL] at {@code minute} after the anchor; 0 at the anchor. */
    public double halfWidthMgdl(int minute) {
        if (minute <= 0) return 0;
        for (int i = 1; i < KNOTS.length; i++) {
            if (minute <= KNOTS[i]) {
                double f = (double) (minute - KNOTS[i - 1]) / (KNOTS[i] - KNOTS[i - 1]);
                return halfWidthMgdl[i - 1] + f * (halfWidthMgdl[i] - halfWidthMgdl[i - 1]);
            }
        }
        return halfWidthMgdl[halfWidthMgdl.length - 1];
    }

    /** Half-width of the cone [mmol/L] at {@code minute}. */
    public double halfWidthMmol(int minute) {
        return halfWidthMgdl(minute) / EmpiricalTwinEngine.MGDL_PER_MMOL;
    }

    /** Half-widths at {@link #KNOTS} [mg/dL]. */
    public double[] knotsMgdl() {
        return halfWidthMgdl.clone();
    }

    private static int nearestKnot(int minute) {
        int best = 1;
        for (int i = 1; i < KNOTS.length; i++) {
            if (Math.abs(KNOTS[i] - minute) < Math.abs(KNOTS[best] - minute)) best = i;
        }
        return best;
    }

    @Override
    public String toString() {
        return "EmpiricalForecastCone" + Arrays.toString(halfWidthMgdl);
    }
}
