package che.glucosemonitorbe.hovorka;

import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.special.Gamma;

/**
 * Time course of one meal's glucose appearance: a Gamma distribution over minutes after eating,
 * parameterised by its mode (peak time) and standard deviation (width).
 *
 * <p>From mode {@code T} and SD {@code σ}: {@code θ = (−T + √(T² + 4σ²)) / 2}, {@code k = 1 + T/θ},
 * so that {@code (k−1)θ = T} and {@code kθ² = σ²} exactly. The mass appearing in minute
 * {@code (a, b]} is {@code cdf(b) − cdf(a)}, which conserves the meal's mass exactly.</p>
 *
 * <p>Bounds: mode in [10, 180] min, SD in [8, 150] min, shape {@code k ≥ 1.5} (a hump, never a
 * spike at t = 0), and at least 99% of the mass by 480 min (σ is shrunk with the mode fixed).</p>
 */
public record GammaKernel(double k, double theta) {

    public static final double MODE_MIN = 10;
    public static final double MODE_MAX = 180;
    public static final double SD_MIN = 8;
    public static final double SD_MAX = 150;
    public static final double K_MIN = 1.5;
    public static final double MAX_Q99_MIN = 480;

    public static GammaKernel fromModeAndSd(double modeMin, double sdMin) {
        double t = clamp(modeMin, MODE_MIN, MODE_MAX);
        double sd = clamp(sdMin, SD_MIN, SD_MAX);
        GammaKernel g = exact(t, sd);
        if (g.quantile(0.99) <= MAX_Q99_MIN) return g;
        // Shrink σ (mode fixed) until 99% of the mass appears by 480 min.
        double lo = SD_MIN, hi = sd;
        while (hi - lo > 1.0) {
            double mid = (lo + hi) / 2;
            if (exact(t, mid).quantile(0.99) <= MAX_Q99_MIN) lo = mid; else hi = mid;
        }
        return exact(t, lo);
    }

    private static GammaKernel exact(double t, double sd) {
        double theta = (-t + Math.sqrt(t * t + 4 * sd * sd)) / 2;
        double k = 1 + t / theta;
        if (k < K_MIN) {
            k = K_MIN;
            theta = t / (K_MIN - 1);
        }
        return new GammaKernel(k, theta);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    /** Fraction of the mass appeared by {@code tMin} minutes after eating; 0 for t ≤ 0. */
    public double cdf(double tMin) {
        if (tMin <= 0) return 0.0;
        return Gamma.regularizedGammaP(k, tMin / theta);
    }

    /** Appearance density [per min] at {@code tMin}; 0 for t ≤ 0. */
    public double pdf(double tMin) {
        if (tMin <= 0) return 0.0;
        return new GammaDistribution(null, k, theta).density(tMin);
    }

    public double quantile(double p) {
        return new GammaDistribution(null, k, theta).inverseCumulativeProbability(p);
    }

    public double mode() {
        return (k - 1) * theta;
    }

    public double sd() {
        return Math.sqrt(k) * theta;
    }
}
