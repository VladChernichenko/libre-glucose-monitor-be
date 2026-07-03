package che.glucosemonitorbe.hovorka.learning;

/**
 * Physiological context of a prediction anchor, used to attribute error and to gate which
 * anchors are trustworthy for calibration.
 *
 * <ul>
 *   <li>{@link #FASTING} — no carbs and no bolus active around the anchor. Isolates
 *       endogenous glucose production / basal drift.</li>
 *   <li>{@link #MEAL} — carbs logged in the active window. Exercises gut absorption + ISF.</li>
 *   <li>{@link #CORRECTION} — bolus but no carbs. Isolates insulin sensitivity.</li>
 * </ul>
 */
public enum Regime {
    FASTING,
    MEAL,
    CORRECTION
}
