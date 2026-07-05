package che.glucosemonitorbe.hupa;

import che.glucosemonitorbe.hovorka.ActivityModulation;
import che.glucosemonitorbe.hovorka.ActivityProvider;
import che.glucosemonitorbe.hupa.HupaUcmDataset.ActivitySample;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * Builds an {@link ActivityProvider} for a HUPA subject: normalized intensity {@code a(t) ∈ [0,1]} from
 * heart-rate reserve {@code (HR − rest)/(max − rest)}, falling back to a normalized steps rate when HR is
 * missing, and 0 when both are absent. Nearest sample within a 5-minute tolerance drives {@code a(t)}.
 */
public final class HupaActivityAdapter implements ActivityProvider {

    public static final double DEFAULT_RESTING_HR = 60.0;
    public static final double DEFAULT_MAX_HR     = 190.0;
    public static final double STEPS_CAP_PER_5MIN = 600.0;
    /**
     * Reserve fraction below which a(t) is treated as 0 — a fixed deadband that keeps ordinary daily
     * heart rate (which sits well above resting) from registering as constant low-grade exercise.
     * With rest 60 / max 190 this means HR must exceed ~99 bpm before activity registers.
     */
    public static final double DEADBAND = 0.30;
    private static final long ALIGN_TOL_MS = 5 * 60 * 1000L;

    private final long[] ts;
    private final double[] a;

    public HupaActivityAdapter(List<ActivitySample> samples) {
        this(samples, DEFAULT_RESTING_HR, DEFAULT_MAX_HR, STEPS_CAP_PER_5MIN);
    }

    public HupaActivityAdapter(List<ActivitySample> samples, double restHr, double maxHr, double stepsCap) {
        List<ActivitySample> sorted = samples.stream()
                .sorted(Comparator.comparingLong(ActivitySample::epochMs)).toList();
        ts = new long[sorted.size()];
        a = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            ts[i] = sorted.get(i).epochMs();
            a[i] = intensity(sorted.get(i).hr(), sorted.get(i).steps(), restHr, maxHr, stepsCap);
        }
    }

    /**
     * a(t) from HR reserve; steps fallback when HR missing; 0 when both absent. A fixed {@link #DEADBAND}
     * excludes ordinary daily heart rate, and the result is rescaled/clamped into [0,1].
     */
    public static double intensity(double hr, double steps, double restHr, double maxHr, double stepsCap) {
        double raw;
        if (hr > 0) raw = (hr - restHr) / (maxHr - restHr);
        else if (steps > 0) raw = steps / stepsCap;
        else return 0.0;
        return applyDeadband(raw);
    }

    /** Zero out the deadband, then rescale the remainder to fill [0,1]. */
    private static double applyDeadband(double rawFraction) {
        double f = ActivityModulation.clampIntensity(rawFraction);
        return f <= DEADBAND ? 0.0 : (f - DEADBAND) / (1.0 - DEADBAND);
    }

    @Override
    public double intensityAt(LocalDateTime time) {
        if (ts.length == 0) return 0.0;
        long target = time.toInstant(ZoneOffset.UTC).toEpochMilli();
        int lo = 0, hi = ts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ts[mid] < target) lo = mid + 1; else hi = mid;
        }
        int best = lo;
        if (lo > 0 && Math.abs(ts[lo - 1] - target) < Math.abs(ts[best] - target)) best = lo - 1;
        return Math.abs(ts[best] - target) <= ALIGN_TOL_MS ? a[best] : 0.0;
    }
}
