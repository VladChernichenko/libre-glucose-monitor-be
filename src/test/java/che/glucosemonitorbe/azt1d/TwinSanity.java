package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.TwinScales;

/** A fitted scale at its floor or ceiling is the signature of a twin that could not explain the data. */
final class TwinSanity {

    private static final double TOLERANCE = 1e-3;

    private TwinSanity() {}

    /** True when the ISF or the meal-size scale sits at a clamp limit. */
    static boolean sitsAtALimit(TwinScales t) {
        return atLimit(t.isfScale()) || atLimit(t.agScale());
    }

    /** Baseline key, per subject so one subject newly at a limit cannot hide behind another leaving it. */
    static String key(String subjectId) {
        return "TWIN_SANITY|" + subjectId;
    }

    private static boolean atLimit(double scale) {
        return Math.abs(scale - TwinScales.MIN_SCALE) < TOLERANCE || Math.abs(scale - TwinScales.MAX_SCALE) < TOLERANCE;
    }
}
