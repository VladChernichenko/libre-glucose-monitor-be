package che.glucosemonitorbe.hovorka.learning;

import java.util.List;

/**
 * Source of {@link AnchorSample}s for a candidate {@link TwinScales}. Implemented by
 * {@link PredictionReplayEngine} (real ODE replay); abstracting it lets the
 * {@link DigitalTwinCalibrator} be unit-tested against synthetic, analytically-known responses.
 */
public interface AnchorSampleSource {

    /** Number of usable anchors available. */
    int anchorCount();

    /** Evaluate the model at every anchor with the given scales and return all comparison samples. */
    List<AnchorSample> replay(TwinScales scales);
}
