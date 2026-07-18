package che.glucosemonitorbe.hovorka;

/**
 * Modulates gastric emptying parameters based on macronutrient composition.
 *
 * <h3>Model sources</h3>
 * <ul>
 *   <li>Caloric density -> half-emptying time: t½ = 9 + 27.5*d, d = kcal/mL (Calvert 1989)</li>
 *   <li>Elashoff β shape coefficients: carbs 1.05, protein 1.60, fat 2.20 (Elashoff 1982)</li>
 *   <li>Fiber viscosity: exponential attenuation of mucosal absorption rate</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 *   double tMaxG = MacroNutrientGastricModel.computeTMaxG(
 *       carbsG, proteinG, fatG, fiberG, HovorkaParameterService.HALF_LIFE_TO_TMAX_G);
 * </pre>
 */
public final class MacroNutrientGastricModel {

    // -- Elashoff (1982) shape parameters -------------------------------------
    public static final double BETA_CARBS   = 1.05;
    public static final double BETA_PROTEIN = 1.60;
    public static final double BETA_FAT     = 2.20;

    // -- Calvert/Hunt caloric-density formula for t½ ---------------------------
    private static final double EMPTYING_INTERCEPT_MIN = 9.0;    // min
    private static final double EMPTYING_DENSITY_COEFF = 27.5;   // min*mL/kcal

    // -- Default assumptions ---------------------------------------------------
    /** Assumed meal volume for caloric density estimate [mL]. */
    private static final double DEFAULT_MEAL_VOLUME_ML  = 250.0;
    /** Fallback t½ when no macros are provided [min]. */
    private static final double FALLBACK_T_HALF_MIN     = 45.0;

    // -- Fiber viscosity -------------------------------------------------------
    /** Per-gram fiber factor for absorption-rate attenuation. */
    private static final double FIBER_VISCOSITY_K = 0.02;

    // -- Physiological clamps --------------------------------------------------
    private static final double T_HALF_MIN_CLAMP = 15.0;   // min
    private static final double T_HALF_MAX_CLAMP = 200.0;  // min

    // -- Bolus strategy thresholds ---------------------------------------------
    private static final double SQUARE_WAVE_FAT_G     = 20.0;
    private static final double SQUARE_WAVE_PROTEIN_G = 15.0;
    private static final double SQUARE_WAVE_PROTEIN_ONLY_G = 40.0;

    private MacroNutrientGastricModel() {}

    /**
     * Compute macro-modulated tMaxG [min] for the Hovorka 2-compartment gut model.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Compute caloric density d [kcal/mL] assuming {@value #DEFAULT_MEAL_VOLUME_ML} mL meal volume.</li>
     *   <li>Base half-emptying time: t½_base = {@value #EMPTYING_INTERCEPT_MIN} + {@value #EMPTYING_DENSITY_COEFF}*d</li>
     *   <li>Weighted β from macronutrient fractions; normalised to carb-only baseline (β_carbs = {@value #BETA_CARBS}).</li>
     *   <li>Fiber viscosity: multiply t½ by exp({@value #FIBER_VISCOSITY_K}*fiberG).</li>
     *   <li>Clamp to [{@value #T_HALF_MIN_CLAMP}, {@value #T_HALF_MAX_CLAMP}] min, then divide by halfLifeToTMaxG.</li>
     * </ol>
     *
     * @param carbsG           grams of available carbohydrates
     * @param proteinG         grams of protein
     * @param fatG             grams of fat
     * @param fiberG           grams of dietary fiber (slows mucosal absorption)
     * @param halfLifeToTMaxG  2-compartment conversion factor (1.68 for Hovorka)
     * @return tMaxG [min] adjusted for macronutrient composition
     */
    public static double computeTMaxG(
            double carbsG, double proteinG, double fatG, double fiberG,
            double halfLifeToTMaxG) {

        double carbKcal    = carbsG   * 4.0;
        double proteinKcal = proteinG * 4.0;
        double fatKcal     = fatG     * 9.0;
        double totalKcal   = carbKcal + proteinKcal + fatKcal;

        if (totalKcal <= 0.0) {
            return FALLBACK_T_HALF_MIN / halfLifeToTMaxG;
        }

        // Step 1 - caloric density
        double d = totalKcal / DEFAULT_MEAL_VOLUME_ML;

        // Step 2 - Calvert base half-life
        double tHalfBase = EMPTYING_INTERCEPT_MIN + EMPTYING_DENSITY_COEFF * d;

        // Step 3 - Elashoff weighted β; normalise to pure-carb baseline
        double betaWeighted = (carbKcal * BETA_CARBS
                             + proteinKcal * BETA_PROTEIN
                             + fatKcal * BETA_FAT) / totalKcal;
        double tHalfModulated = tHalfBase * (betaWeighted / BETA_CARBS);

        // Step 4 - fiber viscosity
        double fiberFactor = Math.exp(FIBER_VISCOSITY_K * Math.max(0.0, fiberG));
        double tHalfFinal  = tHalfModulated * fiberFactor;

        // Step 5 - clamp and convert
        tHalfFinal = Math.max(T_HALF_MIN_CLAMP, Math.min(T_HALF_MAX_CLAMP, tHalfFinal));
        return tHalfFinal / halfLifeToTMaxG;
    }

    /**
     * Weighted average Elashoff β for the given macronutrient mix.
     * Returns {@link #BETA_CARBS} when no macros are specified.
     */
    public static double weightedBeta(double carbsG, double proteinG, double fatG) {
        double carbKcal    = carbsG   * 4.0;
        double proteinKcal = proteinG * 4.0;
        double fatKcal     = fatG     * 9.0;
        double totalKcal   = carbKcal + proteinKcal + fatKcal;
        if (totalKcal <= 0.0) return BETA_CARBS;
        return (carbKcal * BETA_CARBS + proteinKcal * BETA_PROTEIN + fatKcal * BETA_FAT) / totalKcal;
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
