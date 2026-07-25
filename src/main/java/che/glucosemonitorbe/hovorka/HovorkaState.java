package che.glucosemonitorbe.hovorka;

/**
 * Hovorka model extended state vector — 8 ODE variables + 2 tracking fields.
 *
 * <h3>ODE state variables (y[0]..y[7])</h3>
 * <ul>
 *   <li>q1        [0] glucose mass in central compartment [mmol]</li>
 *   <li>q2        [1] glucose mass in peripheral compartment [mmol]</li>
 *   <li>qsto1     [2] Dalla Man solid-stomach compartment [mmol]</li>
 *   <li>qsto2     [3] Dalla Man liquid-stomach compartment [mmol]</li>
 *   <li>qgut      [4] Dalla Man intestinal compartment [mmol]</li>
 *   <li>inc       [5] incretin GLP-1 effect [dimensionless]</li>
 *   <li>x3        [6] delayed insulin action on EGP suppression [dimensionless, 0-1]</li>
 *   <li>protFatGut[7] protein+fat caloric load in gut compartment [kcal] — GLP-1 driver</li>
 * </ul>
 *
 * <h3>Non-ODE tracking fields</h3>
 * <ul>
 *   <li>mealMmol  — Dalla Man D reference [mmol]; refreshed on each new meal ingestion</li>
 *   <li>activeGI  — glycemic index [0-100] of the meal currently being absorbed; default 70</li>
 * </ul>
 */
public record HovorkaState(
        double q1,
        double q2,
        double qsto1,
        double qsto2,
        double qgut,
        double inc,
        double x3,          // NEW: delayed insulin action on EGP (ODE var)
        double protFatGut,  // NEW: protein+fat caloric load in gut (ODE var, GLP-1 driver)
        double mealMmol,
        int    activeGI     // NEW: GI of meal being absorbed [0-100]; default 70
) {
    /**
     * Blood glucose concentration [mmol/L].
     */
    public double glucoseMmolL(HovorkaParameters p) {
        return p.glucoseClamped(q1);
    }

    /**
     * Steady state: Q1/Q2 from current glucose, all gut/incretin/x3/protFatGut at zero.
     * x3 is initialised to 0 here; callers that know the basal x3 should apply
     * {@link #withX3(double)} after construction.
     */
    public static HovorkaState steadyState(double glucoseMmolL, HovorkaParameters p) {
        double q1Init = glucoseMmolL * p.vG();
        double q2Init = q1Init;
        return new HovorkaState(q1Init, q2Init, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 70);
    }

    /**
     * Apply non-negativity constraints to all ODE state variables.
     * mealMmol and activeGI are preserved unchanged (tracking fields, not ODE variables).
     */
    public HovorkaState clampNonNegative() {
        return new HovorkaState(
                Math.max(0.0, q1),
                Math.max(0.0, q2),
                Math.max(0.0, qsto1),
                Math.max(0.0, qsto2),
                Math.max(0.0, qgut),
                Math.max(0.0, inc),
                Math.max(0.0, x3),
                Math.max(0.0, protFatGut),
                mealMmol,
                activeGI
        );
    }

    /** Return a copy with x3 replaced (used to inject basal steady-state x3 after warm-up). */
    public HovorkaState withX3(double newX3) {
        return new HovorkaState(q1, q2, qsto1, qsto2, qgut, inc, newX3, protFatGut, mealMmol, activeGI);
    }
}
