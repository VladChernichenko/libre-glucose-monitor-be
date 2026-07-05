package che.glucosemonitorbe.hovorka;

import java.time.LocalDateTime;

/**
 * Supplies the normalized physical-activity intensity {@code a(t) ∈ [0,1]} to the glucose model
 * (0 = at rest, 1 = maximal effort). Decouples the ODE from the activity data source, mirroring
 * {@link che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider}.
 *
 * <p>The {@link #NONE} implementation returns 0 everywhere, which makes the activity term fully inert
 * — the production default while no live activity feed exists.</p>
 */
@FunctionalInterface
public interface ActivityProvider {

    /** Normalized activity intensity at {@code time}; implementations should keep it within [0,1]. */
    double intensityAt(LocalDateTime time);

    /** No-op provider: always at rest. With this provider predictions are identical to the un-modulated model. */
    ActivityProvider NONE = time -> 0.0;
}
