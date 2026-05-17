package che.glucosemonitorbe.service.observer;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable record describing a detected glucose alert.
 *
 * <p>Priority order (highest first):
 * <ol>
 *   <li>{@link Type#PREDICTED_HYPO}    – path point &lt;3.9 within 60 min</li>
 *   <li>{@link Type#OVER_INJECTION}     – IOB exceeds COB buffer; nadir projected &lt;4.0</li>
 *   <li>{@link Type#RAPID_DROP}         – ROC &lt;−0.07 mmol/L/min for ≥2 readings</li>
 *   <li>{@link Type#UNLOGGED_MEAL}      – ROC &gt;+0.10 sustained &amp; COB=0 &amp; no note 45 min</li>
 *   <li>{@link Type#PREDICTED_HYPER}    – path &gt;12 in 2h &amp; IOB&lt;0.5u</li>
 *   <li>{@link Type#POST_MEAL_SWING}    – ±2 mmol/L beyond expected peak/nadir</li>
 * </ol>
 */
public record GlucoseAlert(
        UUID userId,
        Type type,
        double currentGlucose,
        Double projectedGlucose,   // predicted value at alertTime (null if informational)
        LocalDateTime alertTime,   // when the event is projected to occur
        int minutesUntilEvent,     // 0 = already happening
        String message,
        String actionSuggestion
) {
    public enum Type {
        /** Path point < 3.9 mmol/L within 60 min — APNs critical. */
        PREDICTED_HYPO,

        /**
         * Insulin dose exceeds carb coverage; nadir projected below 4.0.
         * Fires immediately at note-save, before glucose starts moving.
         */
        OVER_INJECTION,

        /** ROC < −0.07 mmol/L/min for ≥ 2 consecutive CGM readings. */
        RAPID_DROP,

        /**
         * Glucose rising fast (ROC > +0.10) with zero active COB and no
         * meal note in the last 45 minutes — user likely forgot to log.
         */
        UNLOGGED_MEAL,

        /** Prediction path exceeds 12 mmol/L within 2 h while IOB < 0.5 u. */
        PREDICTED_HYPER,

        /** Actual peak/nadir deviates > 2 mmol/L from predicted curve. */
        POST_MEAL_SWING
    }

    /** Cooldown minutes before the same alert type may fire again for this user. */
    public int cooldownMinutes() {
        return switch (type) {
            case PREDICTED_HYPO  -> 10;
            case OVER_INJECTION  -> 20;
            case RAPID_DROP      -> 15;
            case UNLOGGED_MEAL   -> 30;
            case PREDICTED_HYPER -> 30;
            case POST_MEAL_SWING -> 60;
        };
    }

    /** True for alerts that should break through Do Not Disturb on iOS. */
    public boolean isCritical() {
        return type == Type.PREDICTED_HYPO || type == Type.OVER_INJECTION;
    }
}
