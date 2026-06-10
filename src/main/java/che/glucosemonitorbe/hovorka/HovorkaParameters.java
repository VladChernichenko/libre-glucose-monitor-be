package che.glucosemonitorbe.hovorka;

/**
 * Hovorka (2004) physiological parameter set for one user.
 *
 * <p>Population constants are from Hovorka et al., "Nonlinear model predictive control of
 * glucose concentration in subjects with type 1 diabetes", Physiol Meas 2004.
 * User-specific values (isf, carbRatio, weight) are derived from experiments and settings.</p>
 *
 * <h3>ODE system (4 state variables)</h3>
 * <pre>
 *   G       = Q1 / vG              [mmol/L]  — blood glucose concentration
 *   F01_c   = f01 * min(1, G/4.5)  [mmol/min] — non-insulin-dependent utilisation
 *
 *   dQ1/dt  = -F01_c - k12*Q1 + k21*Q2 + Ra(t) + egpNet - insulinEffect(t)
 *   dQ2/dt  = k12*Q1 - k21*Q2
 *   dD1/dt  = aG * carbRateMmolPerMin(t) - D1/tMaxG
 *   dD2/dt  = D1/tMaxG - D2/tMaxG
 *   Ra(t)   = D2 / tMaxG
 *
 *   insulinEffect(t) = isf * vG * iobActivityRate(t)   [mmol/min]
 * </pre>
 *
 * @param vG        Glucose distribution volume [L] = 0.16 * weightKg
 * @param f01       Non-insulin-dependent glucose utilisation [mmol/min] = 0.0097 * weightKg
 * @param egpNet    Net endogenous glucose production at steady state [mmol/min] = f01 (by definition)
 * @param k12       Intercompartmental transfer rate Q1→Q2 [/min] (population 0.066)
 * @param k21       Intercompartmental transfer rate Q2→Q1 [/min] (population 0.066)
 * @param tMaxG     Gut absorption time constant [min] = carbHalfLife / 1.68
 * @param aG        Per-user meal-magnitude calibration trim [dimensionless, centred on 1.0].
 *                  NOT a bioavailability factor — physiological carb bioavailability (~0.90) is
 *                  applied once, downstream, by {@code DallaManGutModel.F}. Must not be derived
 *                  from the carb ratio (an insulin-dosing quantity).
 * @param isf       Insulin sensitivity factor [mmol/L per unit] — from user experiment
 * @param weightKg  Body weight [kg] — from cob_settings or 70 kg default
 */
public record HovorkaParameters(
        double vG,
        double f01,
        double egpNet,
        double k12,
        double k21,
        double tMaxG,
        double aG,
        double isf,
        double weightKg
) {
    // ── Population constants (Hovorka 2004, Table 1) ──────────────────────────

    public static final double K12_POP        = 0.066;   // /min
    public static final double K21_POP        = 0.066;   // /min
    public static final double F01_PER_KG     = 0.0097;  // mmol/kg/min
    public static final double EGP0_PER_KG    = 0.0161;  // mmol/kg/min — total hepatic production
    public static final double VG_PER_KG      = 0.16;    // L/kg
    public static final double DEFAULT_WEIGHT = 70.0;    // kg — population fallback

    /** Glucose threshold below which F01 is linearly reduced (mmol/L). */
    public static final double G_THRESHOLD    = 4.5;

    /**
     * Glucose concentration safely clamped to [1, 25] mmol/L.
     * Used by the ODE to prevent runaway numerical values.
     */
    public double glucoseClamped(double q1) {
        return Math.max(1.0, Math.min(25.0, q1 / vG));
    }

    /**
     * Non-insulin-dependent glucose utilisation rate at concentration G.
     * Linearly reduced below G_THRESHOLD (represents renal conservation + brain adaptation).
     */
    public double f01clamped(double g) {
        return f01 * Math.min(1.0, g / G_THRESHOLD);
    }

    /**
     * Effective insulin action volume for the 2-compartment glucose model [L].
     *
     * <p>In a 2-compartment model with k12 = k21, glucose distributes equally between Q1
     * and Q2 at equilibrium (Q1_ss = Q2_ss). When the insulin effect is applied only to Q1,
     * the inter-compartmental buffering (k21 × Q2 → Q1) halves the net Q1 drop, producing
     * a blood glucose (G = Q1/VG) change of only ISF/2 per unit.</p>
     *
     * <p>Multiplying by 2 restores the clinically observed ISF in G, making the model
     * consistent with ISF values measured by the {@code ISF_ONE_UNIT} experiment.</p>
     */
    public double effectiveInsulinVolume() {
        return 2.0 * vG;
    }
}
