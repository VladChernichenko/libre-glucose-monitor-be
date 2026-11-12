package che.glucosemonitorbe.hovorka;

import org.springframework.stereotype.Component;

/**
 * Dalla Man (2007) 3-compartment nonlinear gastrointestinal model.
 *
 * Replaces the linear Hovorka D1/D2 2-compartment chain with a physiologically
 * accurate nonlinear gastric-emptying model. The key improvement is a
 * sigmoid-shaped k_empt that starts fast (full stomach -> rapid emptying),
 * slows at mid-fill (protective pause at 40-80 % full), then rises again as
 * the stomach nears empty.
 *
 * ODE subsystem (per minute, quantities in mmol):
 * <pre>
 *   dQsto1/dt = u_meal(t) - K_GRI × Qsto1
 *   dQsto2/dt = K_GRI × Qsto1 - k_empt(Qsto) × Qsto2
 *   dQgut/dt  = k_empt(Qsto) × Qsto2 - K_ABS × Qgut
 *   Ra(t)     = F × K_ABS × Qgut                         [mmol/min]
 * </pre>
 *
 * k_empt nonlinearity (Dalla Man 2007, eq. 7):
 * <pre>
 *   k_empt(Qsto) = K_MIN + (K_MAX-K_MIN)/2
 *                  × { tanh[α(Qsto − b*D)] − tanh[c(Qsto − d*D)] + 2 }
 *   α = 5 / (2(1−b)D),  c = 5 / (2*d*D),  D = original meal dose [mmol]
 * </pre>
 *
 * Parameters from Dalla Man 2007, Table 1 (population means):
 * K_GRI = K_MAX = 0.0558 min⁻¹, K_MIN = 0.008 min⁻¹,
 * K_ABS = 0.057 min⁻¹, F = 0.90, b = 0.82, d = 0.010.
 */
@Component
public class DallaManGutModel {

    public static final double K_GRI  = 0.0558;   // solid-stomach drain rate [/min]
    public static final double K_MAX  = 0.0558;   // max gastric-emptying rate [/min]
    public static final double K_MIN  = 0.008;    // min gastric-emptying rate [/min]
    public static final double K_ABS  = 0.057;    // intestinal absorption rate [/min]
    public static final double F      = 0.90;     // bioavailable fraction
    public static final double B      = 0.82;     // upper saturation threshold (fraction of D)
    public static final double D_LOW  = 0.010;    // lower saturation threshold (fraction of D)

    /**
     * Carb half-life [min] at which the published rates apply unchanged: {@link #caloricScale}
     * is 1.0 at 45 min.
     */
    public static final double BASE_T_HALF_MIN = 45.0;

    /**
     * Nonlinear gastric emptying rate k_empt [min⁻¹].
     *
     * Shape: near K_MAX at Qsto = D (full), near K_MIN at Qsto = 0.5×D (half-full),
     * back toward midpoint as Qsto -> 0.
     *
     * @param qstoMmol total stomach content Qsto1 + Qsto2 [mmol]
     * @param mealMmol original meal dose [mmol] - reference for saturation thresholds
     */
    public double kEmpt(double qstoMmol, double mealMmol) {
        if (mealMmol <= 0.0) return K_MIN;
        double alpha = 5.0 / (2.0 * (1.0 - B) * mealMmol);
        double c     = 5.0 / (2.0 * D_LOW  * mealMmol);
        return K_MIN + (K_MAX - K_MIN) / 2.0
                * (Math.tanh(alpha * (qstoMmol - B     * mealMmol))
                -  Math.tanh(c     * (qstoMmol - D_LOW * mealMmol))
                + 2.0);
    }

    /**
     * Nonlinear gastric emptying rate with caller-supplied k_max/k_min effective values.
     * Used when the half-time scale C_caloric ({@link #caloricScale}) has been applied upstream.
     *
     * @param qstoMmol  total stomach content Qsto1+Qsto2 [mmol]
     * @param mealMmol  original meal dose [mmol]
     * @param kMaxEff   effective maximum emptying rate [/min] = K_MAX × C_caloric × giScale
     * @param kMinEff   effective minimum emptying rate [/min] = K_MIN × C_caloric × giScale
     */
    public double kEmpt(double qstoMmol, double mealMmol, double kMaxEff, double kMinEff) {
        if (mealMmol <= 0.0) return kMinEff;
        double alpha = 5.0 / (2.0 * (1.0 - B) * mealMmol);
        double c     = 5.0 / (2.0 * D_LOW  * mealMmol);
        return kMinEff + (kMaxEff - kMinEff) / 2.0
                * (Math.tanh(alpha * (qstoMmol - B     * mealMmol))
                -  Math.tanh(c     * (qstoMmol - D_LOW * mealMmol))
                + 2.0);
    }

    /**
     * Rate scale C_caloric = BASE_T_HALF_MIN / t½_meal, clamped to [0.4, 1.5].
     * The solver multiplies k_gri, k_max and k_min by it, so a meal with a longer gut half-time
     * empties proportionally slower and one with a shorter half-time faster.
     *
     * @param tHalfMealMin  the absorbing meal's gut half-time [min], {@code tMaxG × 1.68}
     */
    public static double caloricScale(double tHalfMealMin) {
        double scale = BASE_T_HALF_MIN / Math.max(tHalfMealMin, 1.0);
        return Math.clamp(scale, 0.4, 1.5);
    }

    /**
     * Glucose appearance rate into bloodstream [mmol/min].
     *
     * Ra = F × K_ABS × Qgut.  Qgut is the total intestinal content in mmol
     * (not per-kg), so no body-weight division is required - the result is
     * already an absolute rate comparable to the Hovorka dQ1/dt terms.
     *
     * @param qgutMmol intestinal compartment content [mmol]
     */
    public double ra(double qgutMmol) {
        return F * K_ABS * qgutMmol;
    }

    /**
     * Glucose appearance rate using a caller-supplied absorption constant.
     * Used when the absorbing meal's tMaxG has already been converted to an
     * effective K_ABS via {@link #effectiveKAbs(double)}.
     *
     * @param qgutMmol  intestinal compartment content [mmol]
     * @param kAbsEff   effective absorption rate constant [min⁻¹]
     */
    public double ra(double qgutMmol, double kAbsEff) {
        return F * kAbsEff * qgutMmol;
    }

    // -- tMaxG -> K_ABS scaling -------------------------------------------------

    /**
     * tMaxG [min] at which K_ABS applies unchanged
     * (45-min carb half-life, Hovorka 2-compartment factor 1.68).
     */
    static final double BASE_T_MAX_G_MIN = 45.0 / 1.68;  // ≈ 26.79 min

    /**
     * Effective intestinal absorption rate constant [min⁻¹] for the absorbing meal's
     * gut time constant.
     *
     * <p>The Dalla Man (2007) model uses a fixed K_ABS calibrated for average
     * gastric emptying. Here it is scaled so that the predicted glucose appearance peak
     * moves with {@code tMaxG}. {@code tMaxG} comes from the user's carb half-life, slowed
     * by the meal's fiber, or from the rescue-carb profile.</p>
     *
     * <p>Scale: K_ABS_eff = K_ABS × clamp(BASE_T_MAX_G / tMaxG, 0.2, 2.5).
     * A high tMaxG (a long carb half-life, a fiber-rich meal) reduces the intestinal drain
     * so the Ra peak shifts right; a low tMaxG (a short carb half-life, a hypo treatment)
     * raises it so the rapid spike is captured. The factor is not capped at 1: capped, the
     * model could not absorb fast carbs faster than an average mixed meal and under-predicted
     * their early spike.</p>
     *
     * @param tMaxGMin  the absorbing meal's tMaxG [min] - from {@link HovorkaParameters#tMaxG()}
     * @return effective K_ABS [min⁻¹]
     */
    public static double effectiveKAbs(double tMaxGMin) {
        double factor = BASE_T_MAX_G_MIN / Math.max(tMaxGMin, 1.0);
        // Physiological bounds: never slower than 0.2× nor faster than 2.5× baseline K_ABS.
        factor = Math.max(0.2, Math.min(2.5, factor));
        return K_ABS * factor;
    }
}
