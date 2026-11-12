package che.glucosemonitorbe.hovorka;

/**
 * Two helpers that read a meal's composition: how much its fiber slows absorption, and which bolus
 * strategy its fat and protein call for.
 *
 * <ul>
 *   <li><b>Fiber viscosity</b> ({@link #fiberViscosityFactor}): the gut time constant grows by
 *       {@code exp(0.02 × fiber g)}. The exponential form and the per-gram constant are a
 *       calibration choice, not a measured law.</li>
 *   <li><b>Bolus strategy</b> ({@link #bolusStrategy}): a square-wave bolus is recommended above
 *       fixed fat and protein thresholds.</li>
 * </ul>
 *
 * <p>The meal's size and its protein and fat do not set a gastric half-time here. The stomach
 * load is integrated by the gut model itself ({@link DallaManGutModel}), and protein and fat slow
 * emptying through the ileal brake ({@link HovorkaOdeSolver#brakeKcal}).</p>
 */
public final class MacroNutrientGastricModel {

    // -- Fiber viscosity -------------------------------------------------------
    /** Per-gram fiber factor for absorption-rate attenuation. */
    private static final double FIBER_VISCOSITY_K = 0.02;

    // -- Bolus strategy thresholds ---------------------------------------------
    private static final double SQUARE_WAVE_FAT_G     = 20.0;
    private static final double SQUARE_WAVE_PROTEIN_G = 15.0;
    private static final double SQUARE_WAVE_PROTEIN_ONLY_G = 40.0;

    private MacroNutrientGastricModel() {}

    /**
     * Fiber viscosity multiplier on the gut time constant: {@code exp(K × fiber g)}, 1.0 for no
     * fiber. Shape only - it slows absorption, never removes carbs. The forecast applies it to the
     * user's own {@code tMaxG}, for a logged meal and a prospective one alike.
     */
    public static double fiberViscosityFactor(double fiberG) {
        return Math.exp(FIBER_VISCOSITY_K * Math.max(0.0, fiberG));
    }

    /**
     * Recommend bolus strategy based on fat and protein content.
     *
     * <p>SQUARE_WAVE is indicated when a late glucose rise (3-6 h post-meal) is expected
     * from protein gluconeogenesis or fat-delayed gastric emptying, and a standard
     * meal bolus would under-cover this tail.</p>
     *
     * @return "SQUARE_WAVE" or "NORMAL"
     */
    public static String bolusStrategy(double fatG, double proteinG) {
        if (fatG >= SQUARE_WAVE_FAT_G && proteinG >= SQUARE_WAVE_PROTEIN_G) return "SQUARE_WAVE";
        if (proteinG >= SQUARE_WAVE_PROTEIN_ONLY_G)                          return "SQUARE_WAVE";
        return "NORMAL";
    }
}
