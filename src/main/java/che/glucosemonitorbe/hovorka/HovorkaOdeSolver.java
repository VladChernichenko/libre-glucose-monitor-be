package che.glucosemonitorbe.hovorka;

import org.springframework.stereotype.Component;

/**
 * RK4 numerical integrator for the Hovorka glucose model with
 * Dalla Man (2007) 3-compartment nonlinear gastric absorption and
 * incretin GLP-1 effect.
 *
 * <h3>ODE System (per minute, all quantities in mmol or mmol/min)</h3>
 * <pre>
 *   G      = Q1 / VG
 *   F01_c  = f01 × min(1, G / 4.5)
 *   Qsto   = Qsto1 + Qsto2
 *   kempt  = K_MIN + (K_MAX-K_MIN)/2 × {tanh[α(Qsto−b*D)] − tanh[c(Qsto−d*D)] + 2}
 *   Ra     = F × K_ABS × Qgut
 *
 *   dQ1/dt    = −F01_c − k12*Q1 + k21*Q2 + Ra + EGP(t) − insulinEffect − α_inc*Inc*Q1
 *   dQ2/dt    = k12*Q1 − k21*Q2
 *   dQsto1/dt = −K_GRI*Qsto1               (meal u(t) added before RK4 step)
 *   dQsto2/dt = K_GRI*Qsto1 − kempt*Qsto2
 *   dQgut/dt  = kempt*Qsto2 − K_ABS*Qgut
 *   dInc/dt   = K_INC_PF*protFatGut − K_DEL*Inc
 *   dx3/dt    = -KA3*x3 + KB3*plasmInsulin
 *   dProtFatGut/dt = -K_PF_DRAIN*ProtFatGut
 * </pre>
 */
@Component
public class HovorkaOdeSolver {

    // -- x3 EGP suppression parameters (Hovorka 2004, Table 1) ----------------
    /** x3 deactivation rate [/min] — Hovorka (2004) Table 1. */
    static final double KA3       = 0.03;
    /** x3 induction rate [/min per mU/L]. S_IE ≈ 5×10⁻⁴ → KB3 = KA3 × S_IE. */
    static final double KB3       = 0.000015;
    /** Empirical bridge: mU/L per normalised insulin effect rate unit. */
    static final double V_I_SCALE = 12.0;

    // -- Incretin GLP-1 parameters ---------------------------------------------
    /** Protein+fat gut drain rate [/min] — t½ ≈ 87 min. */
    static final double K_PF_DRAIN = 0.008;
    /** GLP-1 secretion rate [per kcal in gut per min]. */
    static final double K_INC_PF   = 0.003;
    static final double K_DEL    = 0.020;   // clearance rate [/min]  (t½ ≈ 35 min)
    static final double ALPHA_INC = 0.001;  // incretin effect on glucose uptake [/min]

    // -- Renal glucose clearance (Hovorka 2004, eq. 7) -------------------------
    // FR = ke1 × (Q1 − ke2×VG)  if G > ke2, else 0
    static final double KE1 = 0.003;   // renal clearance rate [/min]
    static final double KE2 = 9.0;     // renal glucose threshold [mmol/L]

    // -- Ileal brake: GLP-1 inhibition of gastric emptying --------------------
    // Protein/fat -> GLP-1 rises -> k_empt × Φ_GLP1(Inc) decreases
    // Φ_GLP1(t) = 1 / (1 + KAPPA_GLP1 × Inc(t))  — saturating, never reaches zero
    /** Saturation coefficient for GLP-1 inhibition of gastric emptying [per Inc unit].
     *  Φ_GLP1(t) = 1 / (1 + KAPPA_GLP1 × Inc(t))  — Palumbo (2026). */
    static final double KAPPA_GLP1 = 2.0;

    private final DallaManGutModel gutModel;

    public HovorkaOdeSolver(DallaManGutModel gutModel) {
        this.gutModel = gutModel;
    }

    /**
     * Advance the state by exactly one minute using the classical RK4 method.
     * Delegates to the master 7-arg overload with mealGI=state.activeGI(), protFatKcal=0,
     * activityRate=0.
     *
     * @param state         current 8-variable state + tracking fields
     * @param p             Hovorka parameters
     * @param carbMmolNow   carbs delivered at this minute [mmol] - impulse input to Qsto1
     * @param insulinEffect ISF × VG × iobActivityRate [mmol/min]
     * @return next state after 1-minute integration
     */
    public HovorkaState step(
            HovorkaState state,
            HovorkaParameters p,
            double carbMmolNow,
            double insulinEffect) {
        return step(state, p, carbMmolNow, state.activeGI(), 0.0, insulinEffect, 0.0);
    }

    /**
     * Advance the state by one minute, with an optional insulin-independent activity glucose-uptake
     * rate {@code activityUptakeRate} [per min] (contraction-mediated clearance during exercise);
     * 0 = no activity, which reproduces the un-modulated model exactly.
     * Delegates to the master 7-arg overload with mealGI=state.activeGI(), protFatKcal=0.
     */
    public HovorkaState step(
            HovorkaState state,
            HovorkaParameters p,
            double carbMmolNow,
            double insulinEffect,
            double activityUptakeRate) {
        return step(state, p, carbMmolNow, state.activeGI(), 0.0, insulinEffect, activityUptakeRate);
    }

    /**
     * Master step: advance state by 1 minute with full new inputs.
     *
     * <p>Carbs are an impulse input: add to Qsto1 and refresh the Dalla Man D reference.
     * D (mealMmol) is the saturation reference for k_empt - it must be the stomach
     * content of the <em>current</em> emptying episode, NOT the cumulative sum of every meal
     * ever eaten.</p>
     *
     * @param carbMmolNow    carbs ingested at this minute [mmol]
     * @param mealGI         glycemic index of the arriving carbs [0-100]; use state.activeGI() if no new meal
     * @param protFatKcalNow protein+fat caloric load entering gut at this minute [kcal]
     * @param insulinEffect  glucose removal from bolus insulin [mmol/min]
     * @param activityRate   insulin-independent muscle uptake rate [/min]
     */
    public HovorkaState step(
            HovorkaState state,
            HovorkaParameters p,
            double carbMmolNow,
            int    mealGI,
            double protFatKcalNow,
            double insulinEffect,
            double activityRate) {

        HovorkaState s0 = state;
        int activeGI = state.activeGI();

        if (carbMmolNow > 0) {
            double newQsto1    = state.qsto1() + carbMmolNow;
            double stomachLoad = newQsto1 + state.qsto2();
            activeGI = mealGI;
            s0 = new HovorkaState(
                    state.q1(), state.q2(),
                    newQsto1, state.qsto2(), state.qgut(), state.inc(),
                    state.x3(), state.protFatGut(),
                    stomachLoad, activeGI);
        }
        if (protFatKcalNow > 0) {
            s0 = new HovorkaState(
                    s0.q1(), s0.q2(), s0.qsto1(), s0.qsto2(), s0.qgut(), s0.inc(),
                    s0.x3(), s0.protFatGut() + protFatKcalNow,
                    s0.mealMmol(), s0.activeGI());
        }

        final int gi = activeGI;
        double mealMmol = s0.mealMmol();
        double[] y  = toArray(s0);
        double[] k1 = derivatives(y, p, mealMmol, gi, insulinEffect, activityRate);
        double[] k2 = derivatives(add(y, scale(k1, 0.5)), p, mealMmol, gi, insulinEffect, activityRate);
        double[] k3 = derivatives(add(y, scale(k2, 0.5)), p, mealMmol, gi, insulinEffect, activityRate);
        double[] k4 = derivatives(add(y, k3),             p, mealMmol, gi, insulinEffect, activityRate);

        double[] yn = new double[8];
        for (int i = 0; i < 8; i++) {
            yn[i] = y[i] + (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]) / 6.0;
        }
        return fromArray(yn, mealMmol, gi).clampNonNegative();
    }

    /**
     * Compute the 8 ODE derivatives (4-arg backward-compat delegate).
     *
     * <p>y[0]=Q1, y[1]=Q2, y[2]=Qsto1, y[3]=Qsto2, y[4]=Qgut, y[5]=Inc,
     * y[6]=x3 (EGP insulin-suppression state variable; driven by plasma insulin),
     * y[7]=protFatGut (protein+fat gut load compartment; drives GLP-1 incretin (Inc))</p>
     *
     * @param y             current state as double array (8 elements)
     * @param p             Hovorka parameters
     * @param mealMmol      reference meal dose [mmol] for k_empt (constant per step)
     * @param insulinEffect glucose removal rate from bolus insulin [mmol/min]
     */
    double[] derivatives(double[] y, HovorkaParameters p,
                         double mealMmol, double insulinEffect) {
        // Expand legacy 6-element arrays (pre-Task-4 callers) to 8 elements.
        double[] y8 = y.length >= 8 ? y : java.util.Arrays.copyOf(y, 8);
        return derivatives(y8, p, mealMmol, 70, insulinEffect, 0.0);
    }

    /**
     * Compute the 8 ODE derivatives, with an insulin-independent activity glucose-uptake rate
     * {@code activityUptakeRate} [per min] applied as an extra first-order clearance on Q1.
     *
     * <p>y[6]=x3 (EGP insulin-suppression state variable; driven by plasma insulin).
     * y[7]=protFatGut (protein+fat gut load compartment; drives GLP-1 incretin (Inc)).</p>
     */
    double[] derivatives(double[] y, HovorkaParameters p,
                         double mealMmol, int gi,
                         double insulinEffect, double activityUptakeRate) {

        double q1    = Math.max(0.0, y[0]);
        double q2    = Math.max(0.0, y[1]);
        double qsto1 = Math.max(0.0, y[2]);
        double qsto2 = Math.max(0.0, y[3]);
        double qgut  = Math.max(0.0, y[4]);
        double inc   = Math.max(0.0, y[5]);
        double x3         = Math.max(0.0, y[6]);
        double protFatGut = Math.max(0.0, y[7]);

        double g    = p.glucoseClamped(q1);
        double f01c = p.f01clamped(g);

        // Renal glucose clearance: piecewise-linear above threshold (Hovorka 2004).
        // FR = ke1 × (Q1 − ke2×VG) when G > ke2, zero otherwise.
        double fr = (g > KE2) ? KE1 * (q1 - KE2 * p.vG()) : 0.0;

        // Caloric correction: scale k_max, k_min, and k_gri by C_caloric derived from tMaxG.
        // tMaxG = t½/1.68 → t½_meal = tMaxG * 1.68
        double tHalfMeal = p.tMaxG() * 1.68;
        double cCal      = DallaManGutModel.caloricScale(tHalfMeal);

        // GI scaling: k_abs and k_gri scale linearly with GI/100 (Palumbo 2026).
        // Clamped [0.3, 1.5]: never below 30% (very low GI) nor above 150% (glucose solutions).
        double giScale  = Math.max(0.3, Math.min(1.5, gi / 100.0));

        // Apply GI scale ON TOP OF caloric correction
        double kGriEff  = DallaManGutModel.K_GRI * cCal * giScale;
        double kMaxEff  = DallaManGutModel.K_MAX * cCal * giScale;
        double kMinEff  = DallaManGutModel.K_MIN * cCal * giScale;

        // kAbsEff: existing tMaxG scale PLUS GI scale
        double kAbsEff  = DallaManGutModel.effectiveKAbs(p.tMaxG()) * giScale;

        double qsto  = qsto1 + qsto2;
        double kempt = gutModel.kEmpt(qsto, mealMmol, kMaxEff, kMinEff);

        // Ileal brake: elevated GLP-1 (Inc) inhibits gastric emptying.
        // After protein/fat intake Inc rises via K_INC×Ra, which delays subsequent
        // carb absorption - the "food sequencing" effect (Palumbo modification).
        // Saturating form ensures kemptEff never reaches zero for large Inc.
        double phi      = 1.0 / (1.0 + KAPPA_GLP1 * inc);
        double kemptEff = kempt * phi;

        // Scale K_ABS by the macro-modulated gastric-emptying time (Gap-1 fix) plus GI scale.
        // A high-fat/protein meal has a longer tMaxG -> slower intestinal drain
        // -> Ra peak shifts right without changing total absorbed glucose.
        double ra    = gutModel.ra(qgut, kAbsEff);

        // Approximate plasma insulin I(t) from the insulin effect rate via an empirical bridge.
        // Full S1->S2->I PK model is deferred; this preserves the IOB pharmacokinetics already
        // computed by the OpenAPS curve while avoiding a separate compartment integration.
        double plasmInsulin = (p.isf() * p.effectiveInsulinVolume() > 0)
                ? insulinEffect / (p.isf() * p.effectiveInsulinVolume()) * V_I_SCALE
                : 0.0;

        // x3: delayed insulin action on EGP suppression (Hovorka 2004)
        double dx3 = -KA3 * x3 + KB3 * plasmInsulin;

        // Dynamic EGP: EGP(t) = egp0 × max(0, 1 − x3(t))
        // At basal steady state, x3_ss = 1 - egpNow/egp0, so EGP(ss) = egpNow.
        double egp = p.egp0() * Math.max(0.0, 1.0 - x3);

        // Glucose compartments (activityUptakeRate = insulin-independent, contraction-mediated uptake)
        double dq1 = -f01c - fr - p.k12() * q1 + p.k21() * q2
                   + ra + egp - insulinEffect
                   - ALPHA_INC * inc * q1
                   - activityUptakeRate * q1;
        double dq2 = p.k12() * q1 - p.k21() * q2;

        // Dalla Man gut compartments (use kemptEff to apply ileal brake; kGriEff for GI+caloric scale)
        double dqsto1 = -kGriEff * qsto1;
        double dqsto2 = kGriEff * qsto1 - kemptEff * qsto2;
        double dqgut  = kemptEff * qsto2 - kAbsEff * qgut;

        // Protein+fat gut compartment drains independently (GLP-1 driver)
        double dProtFatGut = -K_PF_DRAIN * protFatGut;

        // Incretin GLP-1: driven by protein+fat transit rate (NOT carb Ra).
        // Inc driven by K_INC_PF × protFatGut (protein/fat gut transit)
        // Pre-loading protein/fat triggers ileal brake before carbs arrive.
        double dinc = K_INC_PF * protFatGut - K_DEL * inc;

        return new double[]{dq1, dq2, dqsto1, dqsto2, dqgut, dinc, dx3, dProtFatGut};
    }

    // -- Array helpers ---------------------------------------------------------

    private double[] toArray(HovorkaState s) {
        return new double[]{s.q1(), s.q2(), s.qsto1(), s.qsto2(), s.qgut(), s.inc(),
                            s.x3(), s.protFatGut()};
    }

    private HovorkaState fromArray(double[] y, double mealMmol, int activeGI) {
        return new HovorkaState(y[0], y[1], y[2], y[3], y[4], y[5], y[6], y[7],
                                mealMmol, activeGI);
    }

    private double[] add(double[] a, double[] b) {
        double[] r = new double[8];
        for (int i = 0; i < 8; i++) r[i] = a[i] + b[i];
        return r;
    }

    private double[] scale(double[] a, double s) {
        double[] r = new double[8];
        for (int i = 0; i < 8; i++) r[i] = a[i] * s;
        return r;
    }
}
