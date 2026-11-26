package che.glucosemonitorbe.hovorka;

/**
 * The CGM deviation: the part of the measured glucose trend that the logged insulin and carbs do
 * not explain, carried into the forecast and faded out.
 *
 * <p>The Hovorka forecast starts its ODE at rest. Whatever is moving glucose that was never logged
 * (an unlogged snack, an under-counted meal, the dawn rise, stress) is otherwise assumed to stop
 * at "now", so a rising CGM gets a forecast that bends flat and falls. The deviation is
 * {@code observed trend - the model's own slope at now}; it enters the glucose compartment as an
 * extra appearance whose weight falls linearly from 1 to 0 over {@link #decayMinutes}. Only rises
 * are carried ({@link #risesOnly}). Same idea as the deviation / unannounced-meal
 * term of OpenAPS oref0 and Loop's retrospective correction; a physiological-model counterpart is
 * the augmented-state Kalman filter of Hajizadeh et al., J Diabetes Sci Technol 2018
 * (doi:10.1177/1932296818789951).</p>
 *
 * <p>Live forecasts only. Calibration replays keep it off so the digital twin fits physiology, not
 * the correction.</p>
 *
 * <h3>Configuration</h3>
 * <p>{@code -Dhovorka.deviationDecayMin=...}; {@code 0} disables it.
 * {@code -Dhovorka.deviationRisesOnly=false} also carries falls.</p>
 */
public final class CgmDeviationModel {

    /**
     * Fade-out of the carried deviation [min], chosen by {@code ForecastSnapshotBacktestTest} on
     * one user's frozen forecasts (2026-11), with the deviation spread over VG. Under Gamma meal
     * absorption (the default; 109 forecasts): MAE at +30/+60/+120/+240 min
     * 1.32/2.06/3.36/4.11 -> 1.36/2.08/3.19/3.82, bias at +120/+240 +2.47/+3.45 -> +1.53/+2.63
     * mmol/L; meal anchors improve at every horizon, correction anchors lose a little in the first
     * hour (MAE at +60 1.87 -> 2.05). A 180-min fade is slightly better past 2 h. Under Dalla Man
     * it was 1.30/2.14/3.47/3.76 -> 1.35/2.16/3.34/3.54. Spread over 2·VG it overshot the first
     * hour. One user; re-check as snapshots accumulate.
     */
    public static final int DEFAULT_DECAY_MIN = 120;

    /** Minutes over which the model's own slope at "now" is averaged. */
    public static final int SLOPE_WINDOW_MIN = 10;

    /** Largest deviation carried [mmol/L per min]; a CGM difference beyond this is noise. */
    public static final double MAX_DEVIATION_MMOL_PER_MIN = 0.15;

    /** Fade-out [min] used by the live forecast; 0 disables. Global model configuration. */
    public static volatile int decayMinutes = Integer.parseInt(
            System.getProperty("hovorka.deviationDecayMin", Integer.toString(DEFAULT_DECAY_MIN)));

    /**
     * Carry only rises the logged events do not explain; a deviation below 0 counts as 0. A
     * carried fall made every forecast after a hypo treatment worse (the fall before the rescue
     * was projected past it) and did not help elsewhere; oref0 carries rises only (UAM) as well.
     */
    public static volatile boolean risesOnly = Boolean.parseBoolean(
            System.getProperty("hovorka.deviationRisesOnly", "true"));

    private CgmDeviationModel() {}

    /**
     * The unexplained rate [mmol/L per min]: the observed trend (clamped as the lag model clamps
     * it) minus the model's slope, clamped to {@link #MAX_DEVIATION_MMOL_PER_MIN}. 0 when the
     * trend is unknown (not finite).
     */
    public static double deviation(double observedTrendMmolPerMin, double modelSlopeMmolPerMin) {
        if (!Double.isFinite(observedTrendMmolPerMin) || !Double.isFinite(modelSlopeMmolPerMin)) return 0.0;
        double trend = clamp(observedTrendMmolPerMin, InterstitialLagModel.MAX_TREND_MMOL_PER_MIN);
        double deviation = clamp(trend - modelSlopeMmolPerMin, MAX_DEVIATION_MMOL_PER_MIN);
        return risesOnly ? Math.max(0.0, deviation) : deviation;
    }

    /** Weight of the deviation over the step ending at {@code minute}: linear from 1 to 0 at {@code decayMin}. */
    public static double weight(int minute, int decayMin) {
        if (decayMin <= 0) return 0.0;
        return Math.max(0.0, 1.0 - (minute - 0.5) / decayMin);
    }

    private static double clamp(double v, double limit) {
        return Math.max(-limit, Math.min(limit, v));
    }
}
