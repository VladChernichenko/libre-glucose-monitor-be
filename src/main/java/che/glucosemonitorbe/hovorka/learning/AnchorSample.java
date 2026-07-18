package che.glucosemonitorbe.hovorka.learning;

/**
 * One (predicted, actual) comparison produced by replaying the Hovorka model from a historical
 * anchor. Many samples per anchor - one for each emitted horizon that has a matching actual CGM
 * reading.
 *
 * @param horizonMin   minutes from the anchor to this point (e.g. 30, 60, 120...)
 * @param predicted    model-predicted glucose at the horizon [mmol/L]
 * @param actual       the CGM reading that actually occurred at the horizon [mmol/L]
 * @param baseline     glucose at the anchor [mmol/L] - the naive "no-change" persistence forecast
 * @param regime       physiological context of the anchor (for gating / attribution)
 * @param hourOfDay    clock hour (0-23) of the predicted point - key for the {@link ResidualBiasModel}
 */
public record AnchorSample(
        int horizonMin,
        double predicted,
        double actual,
        double baseline,
        Regime regime,
        int hourOfDay
) {
    /** Prediction error [mmol/L]: model minus reality. Positive = model over-predicted. */
    public double error() {
        return predicted - actual;
    }

    /** Absolute prediction error [mmol/L]. */
    public double absError() {
        return Math.abs(error());
    }
}
