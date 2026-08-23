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
 *   dQ1/dt    = −F01_c − k12*Q1 + k21*Q2 + Ra + EGP(t) − insulinEffect
 *   dQ2/dt    = k12*Q1 − k21*Q2
 *   dQsto1/dt = −K_GRI*Qsto1               (meal u(t) added before RK4 step)
 *   dQsto2/dt = K_GRI*Qsto1 − kempt*Qsto2
 *   dQgut/dt  = kempt*Qsto2 − K_ABS*Qgut
 *   dInc/dt   = K_INC_PF*ProtFatGut/(ProtFatGut+K_M_PF) − K_DEL*Inc   [Inc ∈ 0..1]
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
    /**
     * Half-saturation protein+fat gut load for the GLP-1 response [kcal].
     *
     * <p>L-cell GLP-1 secretion saturates with nutrient load; it is not proportional to it.
     * Half-max at 200 kcal spreads the response across the realistic range: a 100 kcal snack
     * reaches 33 % activation, a 390 kcal mixed meal 66 %, a 1000 kcal meal 83 %. This is a
     * calibration choice, not a measured constant.</p>
     */
    static final double K_M_PF     = 200.0;
    /**
     * GLP-1 activation rate at a saturating protein+fat load [/min].
     *
     * <p>{@code Inc} is a dimensionless activation <b>fraction</b>: its quasi-steady value is
     * {@code (K_INC_PF / K_DEL) × saturation}, so setting {@code K_INC_PF = K_DEL} bounds it at
     * 1.0 for any meal. Previously {@code Inc} was driven linearly by the kcal in the gut, giving
     * {@code Inc ≈ 0.15 × kcal} — order 30 for a mixed meal and order 150 for a large one, which
     * drove the ileal brake to a full stop and, through the old insulin-independent uptake term,
     * consumed glucose faster than F01.</p>
     */
    static final double K_INC_PF   = 0.020;
    static final double K_DEL    = 0.020;   // clearance rate [/min]  (t½ ≈ 35 min)

    // -- Renal glucose clearance (Hovorka 2004, eq. 7) -------------------------
    // FR = ke1 × (Q1 − ke2×VG)  if G > ke2, else 0
    static final double KE1 = 0.003;   // renal clearance rate [/min]
    static final double KE2 = 9.0;     // renal glucose threshold [mmol/L]

    // -- Ileal brake: GLP-1 inhibition of gastric emptying --------------------
    // Protein/fat -> GLP-1 rises -> k_empt × Φ_GLP1(Inc) decreases
    // Φ_GLP1(t) = 1 / (1 + KAPPA_GLP1 × Inc(t))  — saturating, never reaches zero
    /**
     * Strength of GLP-1 inhibition of gastric emptying [per Inc unit].
     * {@code Φ_GLP1 = 1 / (1 + KAPPA_GLP1 × Inc)} — Palumbo (2026).
     *
     * <p>With {@code Inc} bounded at 1.0, {@code KAPPA_GLP1 = 1.0} floors Φ at 0.5: the most a
     * protein/fat load can do is halve gastric emptying, matching the roughly doubled gastric
     * half-emptying time reported for high-fat/high-protein mixed meals. Note this composes with
     * {@link DallaManGutModel#caloricScale} and, on the {@code /api/predict} path, with
     * {@link MacroNutrientGastricModel}'s tMaxG — fat and protein currently slow absorption
     * through more than one channel.</p>
     */
    static final double KAPPA_GLP1 = 1.0;

    /** GI assumed for a meal that carries no glycemic-index estimate. */
    public static final int DEFAULT_GI = 70;

    /**
     * Absorption-rate multiplier for a meal's glycemic index (Palumbo 2026): k_abs, k_gri, k_max
     * and k_min all scale linearly with GI/100, clamped to [0.3, 1.5] - never below 30% (very low
     * GI) nor above 150% (glucose solutions).
     *
     * <p>Shared with {@link HovorkaGlucosePredictionService}'s warm-up replay so an already-logged
     * meal and a prospective one scale identically.</p>
     */
    public static double giScale(int gi) {
        return Math.max(0.3, Math.min(1.5, gi / 100.0));
    }

    /**
     * GLP-1 activation derivative [per min] for a gut protein+fat load and current activation.
     * Saturating in {@code protFatGut}, so {@code Inc} converges to a fraction in [0, 1].
     *
     * <p>Shared with {@link HovorkaGlucosePredictionService}'s warm-up replay. Hand-copying this
     * expression there once left the warm-up on an older linear form, producing {@code Inc = 7.8}
     * where the forward path produced 0.013 — a meal one minute old absorbed five times slower
     * than the same meal one minute ahead.</p>
     */
    public static double dIncDt(double protFatGut, double inc) {
        double saturation = protFatGut / (protFatGut + K_M_PF);
        return K_INC_PF * saturation - K_DEL * inc;
    }

    /**
     * Ileal-brake multiplier Φ_GLP1 applied to gastric emptying for a GLP-1 activation
     * {@code inc}. Bounded below by {@code 1 / (1 + KAPPA_GLP1)} since {@code Inc <= 1}.
     * Shared with the warm-up replay for the same reason as {@link #dIncDt}.
     */
    public static double ilealBrake(double inc) {
        return 1.0 / (1.0 + KAPPA_GLP1 * inc);
    }

    private final DallaManGutModel gutModel;

    public HovorkaOdeSolver(DallaManGutModel gutModel) {
        this.gutModel = gutModel;
    }

    /**
     * Advance the state by exactly one minute using the classical RK4 method.
     * Delegates to the aggregate-effect 7-arg overload with mealGI=state.activeGI(),
     * protFatKcal=0, activityRate=0.
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
     * Delegates to the aggregate-effect 7-arg overload with mealGI=state.activeGI(), protFatKcal=0.
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
     * Advance state by 1 minute from a single aggregate insulin effect, with no per-dose
     * breakdown. The insulin activity rate driving plasma insulin is recovered from the effect by
     * {@link #impliedActivityRate}, which is exact for a caller whose doses all share one ISF.
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
        return step(state, p, carbMmolNow, mealGI, protFatKcalNow,
                insulinEffect, impliedActivityRate(p, insulinEffect), activityRate);
    }

    /**
     * Master step: advance state by 1 minute with the insulin activity rate supplied explicitly.
     *
     * <p>{@code insulinEffect} and {@code insulinActivityRate} are two views of the same insulin
     * action and must come from the same source: the effect is the glucose removed from Q1
     * [mmol/min], the rate is the insulin being consumed to remove it [units/min]. The caller sums
     * both across active doses, so a forecast whose doses carry different meal-window ISFs still
     * drives plasma insulin from an unweighted rate sum. See {@link #derivatives}.</p>
     *
     * @param insulinActivityRate summed IOB activity rate across active doses [units/min]
     */
    public HovorkaState step(
            HovorkaState state,
            HovorkaParameters p,
            double carbMmolNow,
            int    mealGI,
            double protFatKcalNow,
            double insulinEffect,
            double insulinActivityRate,
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
        double[] k1 = derivatives(y, p, mealMmol, gi, insulinEffect, insulinActivityRate, activityRate);
        double[] k2 = derivatives(add(y, scale(k1, 0.5)), p, mealMmol, gi, insulinEffect, insulinActivityRate, activityRate);
        double[] k3 = derivatives(add(y, scale(k2, 0.5)), p, mealMmol, gi, insulinEffect, insulinActivityRate, activityRate);
        double[] k4 = derivatives(add(y, k3),             p, mealMmol, gi, insulinEffect, insulinActivityRate, activityRate);

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
        return derivatives(y8, p, mealMmol, 70, insulinEffect,
                impliedActivityRate(p, insulinEffect), 0.0);
    }

    /**
     * Convenience overload for callers that supply a single aggregate insulin effect with no
     * per-dose breakdown. The activity rate is recovered by inverting the effect through the
     * parameter ISF, which is exact for a single-ISF caller. The production path uses the
     * explicit-rate overload instead.
     *
     * <p>Kept deliberately: for an aggregate-effect caller the division genuinely is the correct
     * inverse, so this is a shim, not a leftover. It is the only place the ISF quotient survives.</p>
     */
    private static double impliedActivityRate(HovorkaParameters p, double insulinEffect) {
        double denom = p.isf() * p.effectiveInsulinVolume();
        return denom > 0 ? insulinEffect / denom : 0.0;
    }

    /**
     * Compute the 8 ODE derivatives, with an insulin-independent activity glucose-uptake rate
     * {@code activityUptakeRate} [per min] applied as an extra first-order clearance on Q1.
     * The insulin activity rate is recovered from the effect via {@link #impliedActivityRate}.
     *
     * <p>y[6]=x3 (EGP insulin-suppression state variable; driven by plasma insulin).
     * y[7]=protFatGut (protein+fat gut load compartment; drives GLP-1 incretin (Inc)).</p>
     */
    double[] derivatives(double[] y, HovorkaParameters p,
                         double mealMmol, int gi,
                         double insulinEffect, double activityUptakeRate) {
        return derivatives(y, p, mealMmol, gi, insulinEffect,
                impliedActivityRate(p, insulinEffect), activityUptakeRate);
    }

    /**
     * Compute the 8 ODE derivatives with the insulin activity rate supplied explicitly.
     *
     * <p>y[6]=x3 (EGP insulin-suppression state variable; driven by plasma insulin).
     * y[7]=protFatGut (protein+fat gut load compartment; drives GLP-1 incretin (Inc)).</p>
     *
     * @param insulinEffect       glucose removal rate from bolus insulin [mmol/min]
     * @param insulinActivityRate summed IOB activity rate driving that removal [units/min]
     */
    double[] derivatives(double[] y, HovorkaParameters p,
                         double mealMmol, int gi,
                         double insulinEffect, double insulinActivityRate,
                         double activityUptakeRate) {

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
        double giScale  = giScale(gi);

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
        double kemptEff = kempt * ilealBrake(inc);

        // Scale K_ABS by the macro-modulated gastric-emptying time (Gap-1 fix) plus GI scale.
        // A high-fat/protein meal has a longer tMaxG -> slower intestinal drain
        // -> Ra peak shifts right without changing total absorbed glucose.
        double ra    = gutModel.ra(qgut, kAbsEff);

        // Approximate plasma insulin I(t) from the insulin activity rate via an empirical bridge.
        // Full S1->S2->I PK model is deferred; this preserves the IOB pharmacokinetics already
        // computed by the OpenAPS curve while avoiding a separate compartment integration.
        //
        // Plasma insulin is driven by how fast insulin is acting, not by how much glucose that
        // action removes. The caller supplies the summed IOB activity rate directly: the effect is
        // Sum(dose.isf x V x rate), so dividing it by any single ISF was exact only while every
        // dose shared one - which per-meal-window ISF no longer guarantees.
        double plasmInsulin = insulinActivityRate * V_I_SCALE;

        // x3: delayed insulin action on EGP suppression (Hovorka 2004)
        double dx3 = -KA3 * x3 + KB3 * plasmInsulin;

        // Dynamic EGP: EGP(t) = egp0 × max(0, 1 − x3(t))
        // At basal steady state, x3_ss = 1 - egpNow/egp0, so EGP(ss) = egpNow.
        double egp = p.egp0() * Math.max(0.0, 1.0 - x3);

        // Glucose compartments (activityUptakeRate = insulin-independent, contraction-mediated uptake)
        // Inc does NOT appear here: GLP-1 lowers glucose through insulin secretion (absent in
        // T1D), glucagon suppression, and delayed gastric emptying — the last of which is already
        // modelled by kemptEff above. The old -ALPHA_INC*Inc*Q1 term added a fourth,
        // insulin-independent uptake pathway with no basis in T1D physiology, and at Inc ≈ 30 it
        // cleared glucose ~3x faster than F01: a 60 g meal with 30 g protein and 30 g fat and no
        // bolus at all was predicted to fall from 6.0 to 2.2 mmol/L.
        double dq1 = -f01c - fr - p.k12() * q1 + p.k21() * q2
                   + ra + egp - insulinEffect
                   - activityUptakeRate * q1;
        double dq2 = p.k12() * q1 - p.k21() * q2;

        // Dalla Man gut compartments (use kemptEff to apply ileal brake; kGriEff for GI+caloric scale)
        double dqsto1 = -kGriEff * qsto1;
        double dqsto2 = kGriEff * qsto1 - kemptEff * qsto2;
        double dqgut  = kemptEff * qsto2 - kAbsEff * qgut;

        // Protein+fat gut compartment drains independently (GLP-1 driver)
        double dProtFatGut = -K_PF_DRAIN * protFatGut;

        // Incretin GLP-1: driven by protein+fat in the gut (NOT carb Ra), saturating.
        // Pre-loading protein/fat triggers the ileal brake before carbs arrive.
        double dinc = dIncDt(protFatGut, inc);

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
