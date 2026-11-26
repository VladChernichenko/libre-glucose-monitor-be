package che.glucosemonitorbe.hovorka;

/**
 * The meal-absorption model a forecast runs and a digital twin was fitted under
 * ({@code app.features.gamma-absorption-enabled}). A twin's scales and residual grid correct the
 * errors of one model; applied to the other they correct errors it does not have.
 */
public enum AbsorptionModel {
    /** Dalla Man 3-compartment gut ({@link MealGutChains}). */
    DALLA_MAN,
    /** Per-meal Gamma kernels ({@link GammaMealAppearance}). */
    GAMMA;

    /** The model the forecast runs for the given flag value. */
    public static AbsorptionModel of(boolean gammaAbsorptionEnabled) {
        return gammaAbsorptionEnabled ? GAMMA : DALLA_MAN;
    }

    /**
     * A stored model name; {@code null} or unknown counts as {@link #DALLA_MAN}, the only model
     * twins were fitted under before the name was recorded.
     */
    public static AbsorptionModel parse(String name) {
        if (name == null) return DALLA_MAN;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return DALLA_MAN;
        }
    }
}
