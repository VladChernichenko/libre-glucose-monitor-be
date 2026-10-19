package che.glucosemonitorbe.domain;

/**
 * Which engine forecasts a user's dashboard prediction path ({@code user_settings.prediction_engine}).
 */
public enum PredictionEngine {
    /** The Hovorka path only (default). */
    HOVORKA,
    /** The empirical twin's path in place of the Hovorka path. */
    EMPIRICAL,
    /** The Hovorka path, plus the empirical twin's path as a second series. */
    BOTH
}
