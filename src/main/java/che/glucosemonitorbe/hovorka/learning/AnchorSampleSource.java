package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.hovorka.ActivityGains;

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

    /** As above with per-user activity gains; sources that do not model activity ignore them. */
    default List<AnchorSample> replay(TwinScales scales, ActivityGains gains) {
        return replay(scales);
    }
}
