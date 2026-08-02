package che.glucosemonitorbe.exception;

/**
 * Why a dose calculation was refused. The message is patient-facing and must never
 * contain internal state - the {@code detail} on {@link DosingRefusedException} carries
 * that, and is logged rather than returned.
 */
public enum DosingRefusalReason {

    INVALID_INPUT(
            "The dose request was incomplete or outside the accepted range."),

    SETTINGS_INVALID(
            "Insulin sensitivity and carb ratio must be configured before a dose can be calculated."),

    INSULIN_PARAMS_INCONSISTENT(
            "The configured insulin sensitivity and carb ratio are inconsistent. "
                    + "Please review these settings with your clinician."),

    GLUCOSE_BELOW_SAFE_THRESHOLD(
            "Glucose is below the safe threshold for bolusing. Treat the low first."),

    GLUCOSE_IMPLAUSIBLE_UNIT(
            "Glucose is outside the physiological range for mmol/L. Check that the value is not in mg/dL."),

    DOSE_EXCEEDS_MAX_BOLUS(
            "The calculated dose exceeds the maximum safe bolus. Please check the inputs.");

    private final String message;

    DosingRefusalReason(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
