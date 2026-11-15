package che.glucosemonitorbe.hovorka.learning;

/**
 * Physiological context of a prediction anchor, used to attribute error and to gate which
 * anchors are trustworthy for calibration.
 *
 * <ul>
 *   <li>{@link #FASTING} - no carbs and no bolus active around the anchor. Isolates
 *       endogenous glucose production / basal drift.</li>
 *   <li>{@link #MEAL} - carbs logged in the active window. Exercises gut absorption + ISF.</li>
 *   <li>{@link #CORRECTION} - no carbs, and every active bolus was logged as a correction at a
 *       high reading. Isolates insulin sensitivity; the only regime the ISF scale is fitted on.</li>
 *   <li>{@link #INSULIN_ONLY} - bolus but no carbs, and not all of it a trusted correction (a
 *       pre-bolus, or a "correction" near target). Often a meal whose carbs were never logged, so
 *       it must not shape insulin sensitivity.</li>
 * </ul>
 */
public enum Regime {
    FASTING,
    MEAL,
    CORRECTION,
    INSULIN_ONLY
}
