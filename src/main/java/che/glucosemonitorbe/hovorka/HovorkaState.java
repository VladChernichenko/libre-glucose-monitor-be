package che.glucosemonitorbe.hovorka;

/**
 * Hovorka model state vector — 4 differential state variables.
 *
 * <ul>
 *   <li><b>q1</b> — glucose mass in central (plasma) compartment [mmol]</li>
 *   <li><b>q2</b> — glucose mass in peripheral compartment [mmol]</li>
 *   <li><b>d1</b> — carbohydrates in gut compartment 1 (stomach) [mmol]</li>
 *   <li><b>d2</b> — carbohydrates in gut compartment 2 (intestine) [mmol]</li>
 * </ul>
 *
 * <p>Blood glucose concentration G = q1 / vG [mmol/L].</p>
 * <p>Glucose appearance rate Ra = d2 / tMaxG [mmol/min].</p>
 */
public record HovorkaState(
        double q1,   // mmol — central glucose compartment
        double q2,   // mmol — peripheral glucose compartment
        double d1,   // mmol — gut compartment 1 (stomach)
        double d2    // mmol — gut compartment 2 (intestine)
) {
    /**
     * Blood glucose concentration [mmol/L].
     *
     * @param p Hovorka parameters containing vG
     */
    public double glucoseMmolL(HovorkaParameters p) {
        return p.glucoseClamped(q1);
    }

    /**
     * Gut glucose appearance rate into bloodstream [mmol/min].
     * Ra(t) = D2 / t_max_G
     */
    public double ra(HovorkaParameters p) {
        return d2 / p.tMaxG();
    }

    /**
     * Construct the physiological steady-state for a given fasting glucose.
     * At steady state (no meals, no bolus, stable basal):
     * <ul>
     *   <li>Q2_ss = Q1_ss (since k12 = k21)</li>
     *   <li>D1 = D2 = 0</li>
     * </ul>
     */
    public static HovorkaState steadyState(double glucoseMmolL, HovorkaParameters p) {
        double q1Init = glucoseMmolL * p.vG();
        // At SS with k12 = k21: dQ2/dt = 0 → Q2 = Q1
        double q2Init = q1Init;
        return new HovorkaState(q1Init, q2Init, 0.0, 0.0);
    }

    /**
     * Apply non-negativity constraints to all state variables.
     * Required after RK4 integration steps to prevent numerical underflow.
     */
    public HovorkaState clampNonNegative() {
        return new HovorkaState(
                Math.max(0.0, q1),
                Math.max(0.0, q2),
                Math.max(0.0, d1),
                Math.max(0.0, d2)
        );
    }
}
