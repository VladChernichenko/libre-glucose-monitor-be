package che.glucosemonitorbe.domain;

/**
 * Single definition of the mg/dL to mmol/L glucose conversion.
 *
 * <p>This factor previously appeared as a private constant in three unrelated classes. Glucose
 * conversion is a medical calculation; one definition removes the chance of them drifting apart.
 */
public final class GlucoseConversion {

    private GlucoseConversion() {}

    /** mg/dL per mmol/L of glucose. */
    public static final double MGDL_PER_MMOL = 18.0182;

    public static double mgdlToMmol(double mgdl) {
        return mgdl / MGDL_PER_MMOL;
    }

    public static double mmolToMgdl(double mmol) {
        return mmol * MGDL_PER_MMOL;
    }
}
