package che.glucosemonitorbe.glycemic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds every sustained, same-sign stretch of model residual that clears an adaptive threshold
 * (sigmaMultiple x robust sigma) for at least {@code persistenceMinutes} - the sensor-noise guard.
 * Residuals are median-3 smoothed first so a single noisy reading can't split a real run, and a run
 * never bridges a CGM gap longer than {@code maxGapMinutes}.
 */
public final class ResidualScanner {

    /** CGM sensor-noise floor for sigma [mmol/L] - matches the calibrator. */
    static final double SIGMA_FLOOR = 0.3;

    private final double sigmaMultiple;
    private final int persistenceMinutes;
    private final int maxGapMinutes;
    private final int minReadings;

    public ResidualScanner(double sigmaMultiple, int persistenceMinutes, int maxGapMinutes, int minReadings) {
        this.sigmaMultiple = sigmaMultiple;
        this.persistenceMinutes = persistenceMinutes;
        this.maxGapMinutes = maxGapMinutes;
        this.minReadings = minReadings;
    }

    public static ResidualSeries series(List<ResidualPoint> points) {
        List<Double> raw = new ArrayList<>(points.size());
        for (ResidualPoint p : points) raw.add(p.residual());
        return new ResidualSeries(List.copyOf(points), median3(raw), robustScale(raw));
    }

    public List<ResidualRun> runs(ResidualSeries s) {
        List<ResidualRun> out = new ArrayList<>();
        int n = s.size();
        if (n < minReadings) return out;
        double threshold = sigmaMultiple * s.sigma();
        long maxGapMs = maxGapMinutes * 60_000L;
        int i = 0;
        while (i < n) {
            boolean positive = s.smoothed(i) >= 0;
            int j = i + 1;
            while (j < n && (s.smoothed(j) >= 0) == positive
                    && s.epochMs(j) - s.epochMs(j - 1) <= maxGapMs) {
                j++;
            }
            ResidualRun run = ResidualRun.of(s, i, j - 1, threshold);
            if (run.durationMinutes() >= persistenceMinutes && Math.abs(run.mean()) >= threshold) {
                out.add(run);
            }
            i = j;
        }
        return out;
    }

    public static Optional<ResidualRun> strongest(List<ResidualRun> runs) {
        return runs.stream().max(Comparator.comparingDouble(r -> Math.abs(r.mean())));
    }

    /** Running median of three; endpoints keep their raw value. */
    static List<Double> median3(List<Double> v) {
        List<Double> out = new ArrayList<>(v);
        for (int i = 1; i < v.size() - 1; i++) {
            double a = v.get(i - 1), b = v.get(i), c = v.get(i + 1);
            out.set(i, Math.max(Math.min(a, b), Math.min(Math.max(a, b), c)));
        }
        return out;
    }

    /** Robust spread: 1.4826 x MAD, floored at the sensor-noise level. */
    public static double robustScale(List<Double> values) {
        if (values.isEmpty()) return SIGMA_FLOOR;
        double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
        double median = median(a.clone());
        double[] dev = new double[a.length];
        for (int i = 0; i < a.length; i++) dev[i] = Math.abs(a[i] - median);
        return Math.max(SIGMA_FLOOR, 1.4826 * median(dev));
    }

    private static double median(double[] a) {
        Arrays.sort(a);
        int n = a.length;
        if (n == 0) return 0.0;
        return (n % 2 == 1) ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }
}
