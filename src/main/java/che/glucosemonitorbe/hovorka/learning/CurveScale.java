package che.glucosemonitorbe.hovorka.learning;

/**
 * Personal reshaping of one insulin's action curve, as multipliers on the catalog peak time and
 * duration. It moves <em>when</em> a dose acts, never how much: the total effect of a unit stays
 * with the ISF.
 *
 * @param peakScale multiplier on the catalog peak time
 * @param diaScale  multiplier on the catalog duration of action
 */
public record CurveScale(double peakScale, double diaScale) {

    public static final double MIN_PEAK_SCALE = 0.6;
    public static final double MAX_PEAK_SCALE = 1.6;
    public static final double MIN_DIA_SCALE  = 0.75;
    public static final double MAX_DIA_SCALE  = 1.5;

    /** The catalog curve, unchanged. */
    public static final CurveScale NEUTRAL = new CurveScale(1.0, 1.0);

    /** A copy with both multipliers inside their bounds; a non-finite one becomes neutral. */
    public CurveScale clamped() {
        return new CurveScale(clamp(peakScale, MIN_PEAK_SCALE, MAX_PEAK_SCALE),
                clamp(diaScale, MIN_DIA_SCALE, MAX_DIA_SCALE));
    }

    /** Peak time [min] for a catalog peak, kept inside the scaled curve (the IOB model needs peak < duration/2). */
    public double peakMinutes(double catalogPeakMinutes, double catalogDiaHours) {
        double endMin = diaHours(catalogDiaHours) * 60.0;
        return Math.max(15.0, Math.min(catalogPeakMinutes * peakScale, endMin / 2.0 - 5.0));
    }

    /** Duration [h] for a catalog duration. */
    public double diaHours(double catalogDiaHours) {
        return catalogDiaHours * diaScale;
    }

    private static double clamp(double v, double lo, double hi) {
        if (!Double.isFinite(v)) return 1.0;
        return Math.max(lo, Math.min(hi, v));
    }
}
