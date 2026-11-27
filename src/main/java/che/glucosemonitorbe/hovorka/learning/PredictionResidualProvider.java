package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.hovorka.ActivityGains;

import java.util.UUID;

/**
 * Supplies the digital twin's learned values to the live forecast: the ISF scale, activity gains and
 * the per-horizon confidence band. (It no longer adds an hourly offset to predicted points.)
 * The {@link #NONE} implementation is used during calibration replay and anywhere the twin should be
 * bypassed.
 */
public interface PredictionResidualProvider {

    /**
     * Predictive standard deviation [mmol/L] at {@code horizonMin} minutes ahead - the half-width
     * driver of the confidence band around the point. Defaults to a population prior so any provider
     * yields a sensible band; {@link #NONE} returns 0 (raw model, no band - used in calibration replay).
     *
     * @param userId     the user whose twin to consult
     * @param horizonMin minutes from "now" to the predicted point
     */
    default double uncertaintySdMmol(UUID userId, int horizonMin) {
        return PredictionUncertaintyModel.populationDefault().sdAtHorizon(horizonMin);
    }

    /**
     * The learned activity gains for the live forecast; population defaults unless the user's twin
     * learned better ones. Replay never goes through this - it passes gains explicitly.
     */
    /**
     * Applied digital-twin ISF multiplier. {@code 1.0} when no twin is applied.
     * Callers clamp with {@link TwinScales} before using the value on a dose.
     */
    default double isfScale(UUID userId) {
        return 1.0;
    }

    default ActivityGains activityGains(UUID userId) {
        return ActivityGains.NEUTRAL;
    }

    /** No-op provider: no band. Used during calibration replay and in tests. */
    PredictionResidualProvider NONE = new PredictionResidualProvider() {
        @Override public double uncertaintySdMmol(UUID userId, int horizonMin) { return 0.0; }
    };
}
