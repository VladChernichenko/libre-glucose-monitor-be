package che.glucosemonitorbe.hovorka;

/**
 * Hovorka model extended state vector.
 *
 * <p><b>ODE state variables</b> (6 differential equations):</p>
 * <ul>
 *   <li><b>q1</b>    - glucose mass in central (plasma) compartment [mmol]</li>
 *   <li><b>q2</b>    - glucose mass in peripheral compartment [mmol]</li>
 *   <li><b>qsto1</b> - Dalla Man solid-stomach compartment [mmol]</li>
 *   <li><b>qsto2</b> - Dalla Man liquid-stomach compartment [mmol]</li>
 *   <li><b>qgut</b>  - Dalla Man intestinal compartment [mmol]</li>
 *   <li><b>inc</b>   - incretin GLP-1 effect level [dimensionless]</li>
 * </ul>
 *
 * <p><b>Non-differential tracking field:</b></p>
 * <ul>
 *   <li><b>mealMmol</b> - Dalla Man D reference [mmol] = stomach load of the current
 *       emptying episode; used by {@link DallaManGutModel#kEmpt} to calibrate the
 *       nonlinear saturation thresholds. Refreshed to the post-ingestion stomach
 *       content when new carbs arrive (NOT a cumulative sum of all past meals);
 *       never integrated by the ODE solver.</li>
 * </ul>
 *
 * <p>Blood glucose: G = q1 / vG [mmol/L].</p>
 * <p>Glucose appearance: Ra = {@link DallaManGutModel#ra(double) F × K_ABS × qgut} [mmol/min].</p>
 */
public record HovorkaState(
        double q1,       // mmol - central glucose compartment
        double q2,       // mmol - peripheral glucose compartment
        double qsto1,    // mmol - solid stomach (Dalla Man)
        double qsto2,    // mmol - liquid stomach (Dalla Man)
        double qgut,     // mmol - intestinal compartment (Dalla Man)
        double inc,      // dimensionless - incretin GLP-1 effect
        double mealMmol  // mmol - reference meal dose for k_empt (not an ODE variable)
) {
    /**
     * Blood glucose concentration [mmol/L].
     */
    public double glucoseMmolL(HovorkaParameters p) {
        return p.glucoseClamped(q1);
    }

    /**
     * Physiological steady state for a given fasting glucose.
     * All gut and incretin compartments are zero (no active meal or GLP-1).
     */
    public static HovorkaState steadyState(double glucoseMmolL, HovorkaParameters p) {
        double q1Init = glucoseMmolL * p.vG();
        double q2Init = q1Init; // k12 = k21 at SS -> Q2 = Q1
        return new HovorkaState(q1Init, q2Init, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * Apply non-negativity constraints to all ODE state variables.
     * mealMmol is preserved unchanged (it is a reference, not an ODE variable).
     */
    public HovorkaState clampNonNegative() {
        return new HovorkaState(
                Math.max(0.0, q1),
                Math.max(0.0, q2),
                Math.max(0.0, qsto1),
                Math.max(0.0, qsto2),
                Math.max(0.0, qgut),
                Math.max(0.0, inc),
                mealMmol
        );
    }
}
