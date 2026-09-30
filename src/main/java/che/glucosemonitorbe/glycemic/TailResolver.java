package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;

import java.util.List;

/**
 * Decisions that need evidence arriving after onset (spec section 4). Pure: callers supply times,
 * bolus times and forecast arrays.
 */
public final class TailResolver {

    public record Config(double peakMinutes, double noEffectRatio, int aerobicMaxMinutes,
                         int anaerobicMaxRiseMinutes, int anaerobicMaxGapMinutes, double effectDenominatorFloor) {}

    /** A drop whose window has not grown for this long is considered to have stopped. */
    private static final long STOPPED_GRACE_MS = 15 * 60_000L;

    private final Config c;

    public TailResolver(Config config) { this.c = config; }

    /** A fall is the delayed slingshot of a resistance parent (4.1 step 1). */
    public boolean isSlingshot(long parentOnsetMs, long parentResolvesMs, long fallOnsetMs, List<Long> bolusEpochMs) {
        if (fallOnsetMs < parentOnsetMs + (long) (c.peakMinutes() * 60_000L) || fallOnsetMs > parentResolvesMs) return false;
        return bolusEpochMs.stream().noneMatch(b -> b > parentOnsetMs && b <= fallOnsetMs);
    }

    /**
     * Insulin effect ratio E = mean(G_noIns - G_actual) / mean(G_noIns - G_pred). Null when the tail is
     * empty or the model expected less than {@code floor} mmol/L of insulin effect (E would be noise).
     */
    public static Double effectRatio(double[] gNoInsulin, double[] gActual, double[] gPredicted, double floor) {
        int n = gActual.length;
        if (n == 0) return null;
        double num = 0.0, den = 0.0;
        for (int i = 0; i < n; i++) {
            num += gNoInsulin[i] - gActual[i];
            den += gNoInsulin[i] - gPredicted[i];
        }
        num /= n;
        den /= n;
        return den < floor ? null : num / den;
    }

    /** No slingshot by resolves_at: insulin had ~no effect -> LIPODYSTROPHY, otherwise STRESS. */
    public Category resolveResistance(Double effectRatio) {
        return effectRatio != null && effectRatio >= 0 && effectRatio < c.noEffectRatio()
                ? Category.LIPODYSTROPHY : Category.STRESS;
    }

    /** A single no-effect case is weak evidence; a bad site repeats every time it is used. */
    public static double lipodystrophyConfidence(long recentNoEffect) {
        return Math.min(0.9, 0.4 + 0.1 * recentNoEffect);
    }

    /** An unexplained drop that stopped within the cardio window is exercise; one that kept going is insulin. */
    public Category resolveDrop(long onsetMs, long windowEndMs, long nowMs) {
        boolean stopped = nowMs - windowEndMs >= STOPPED_GRACE_MS;
        boolean shortEnough = windowEndMs - onsetMs <= c.aerobicMaxMinutes() * 60_000L;
        return stopped && shortEnough ? Category.AEROBIC_ACTIVITY : Category.UNLOGGED_INSULIN;
    }

    /** A short unexplained rise followed promptly by a drop, with no insulin in between (4.3). */
    public boolean isAnaerobicPair(double riseDurationMin, long riseWindowEndMs, long fallOnsetMs, List<Long> bolusEpochMs) {
        if (riseDurationMin > c.anaerobicMaxRiseMinutes()) return false;
        if (fallOnsetMs - riseWindowEndMs > c.anaerobicMaxGapMinutes() * 60_000L) return false;
        long from = Math.min(riseWindowEndMs, fallOnsetMs), to = Math.max(riseWindowEndMs, fallOnsetMs);
        return bolusEpochMs.stream().noneMatch(b -> b >= from && b <= to);
    }
}
