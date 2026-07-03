package che.glucosemonitorbe.hovorka.learning;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Supplies the digital-twin residual correction [mmol/L] to add to a prediction point at emission.
 *
 * <p>Injected into {@link che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService} so the
 * learned {@link ResidualBiasModel} is auto-applied to <b>predictions only</b>. The
 * {@link #NONE} implementation is used during calibration replay (so residuals are measured against
 * the raw physiological model, never double-corrected) and anywhere the twin should be bypassed.</p>
 */
public interface PredictionResidualProvider {

    /**
     * @param userId    the user whose twin to consult
     * @param pointTime clock time of the predicted point (its hour-of-day keys the correction)
     * @return correction [mmol/L] to add to the predicted glucose, or 0 if none
     */
    double residualMmol(UUID userId, LocalDateTime pointTime);

    /**
     * Predictive standard deviation [mmol/L] at {@code horizonMin} minutes ahead — the half-width
     * driver of the confidence band around the point. Defaults to a population prior so any provider
     * yields a sensible band; {@link #NONE} returns 0 (raw model, no band — used in calibration replay).
     *
     * @param userId     the user whose twin to consult
     * @param horizonMin minutes from "now" to the predicted point
     */
    default double uncertaintySdMmol(UUID userId, int horizonMin) {
        return PredictionUncertaintyModel.populationDefault().sdAtHorizon(horizonMin);
    }

    /** No-op provider: no correction and no band. Used during calibration replay and in tests. */
    PredictionResidualProvider NONE = new PredictionResidualProvider() {
        @Override public double residualMmol(UUID userId, LocalDateTime pointTime) { return 0.0; }
        @Override public double uncertaintySdMmol(UUID userId, int horizonMin) { return 0.0; }
    };
}
