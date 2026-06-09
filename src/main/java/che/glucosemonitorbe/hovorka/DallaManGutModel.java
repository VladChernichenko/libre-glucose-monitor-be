package che.glucosemonitorbe.hovorka;

import org.springframework.stereotype.Component;

/**
 * Dalla Man (2007) 3-compartment nonlinear gastrointestinal model.
 *
 * Replaces the linear Hovorka D1/D2 2-compartment chain with a physiologically
 * accurate nonlinear gastric-emptying model. The key improvement is a
 * sigmoid-shaped k_empt that starts fast (full stomach → rapid emptying),
 * slows at mid-fill (protective pause at 40–80 % full), then rises again as
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
 *                  × { tanh[α(Qsto − b·D)] − tanh[c(Qsto − d·D)] + 2 }
 *   α = 5 / (2(1−b)D),  c = 5 / (2·d·D),  D = original meal dose [mmol]
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
     * Nonlinear gastric emptying rate k_empt [min⁻¹].
     *
     * Shape: near K_MAX at Qsto = D (full), near K_MIN at Qsto = 0.5×D (half-full),
     * back toward midpoint as Qsto → 0.
     *
     * @param qstoMmol total stomach content Qsto1 + Qsto2 [mmol]
     * @param mealMmol original meal dose [mmol] — reference for saturation thresholds
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
     * Glucose appearance rate into bloodstream [mmol/min].
     *
     * Ra = F × K_ABS × Qgut.  Qgut is the total intestinal content in mmol
     * (not per-kg), so no body-weight division is required — the result is
     * already an absolute rate comparable to the Hovorka dQ1/dt terms.
     *
     * @param qgutMmol intestinal compartment content [mmol]
     */
    public double ra(double qgutMmol) {
        return F * K_ABS * qgutMmol;
    }
}
