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
 *   kempt  = K_MIN + (K_MAX-K_MIN)/2 × {tanh[α(Qsto−b·D)] − tanh[c(Qsto−d·D)] + 2}
 *   Ra     = F × K_ABS × Qgut
 *
 *   dQ1/dt    = −F01_c − k12·Q1 + k21·Q2 + Ra + egpNet − insulinEffect − α_inc·Inc·Q1
 *   dQ2/dt    = k12·Q1 − k21·Q2
 *   dQsto1/dt = −K_GRI·Qsto1               (meal u(t) added before RK4 step)
 *   dQsto2/dt = K_GRI·Qsto1 − kempt·Qsto2
 *   dQgut/dt  = kempt·Qsto2 − K_ABS·Qgut
 *   dInc/dt   = K_INC·Ra − K_DEL·Inc
 * </pre>
 */
@Component
public class HovorkaOdeSolver {

    // ── Incretin GLP-1 parameters ─────────────────────────────────────────────
    static final double K_INC    = 0.005;   // secretion [min/mmol] per unit Ra
    static final double K_DEL    = 0.020;   // clearance rate [/min]  (t½ ≈ 35 min)
    static final double ALPHA_INC = 0.001;  // incretin effect on glucose uptake [/min]

    private final DallaManGutModel gutModel;

    public HovorkaOdeSolver(DallaManGutModel gutModel) {
        this.gutModel = gutModel;
    }

    /**
     * Advance the state by exactly one minute using the classical RK4 method.
     *
     * @param state         current 6-variable state + meal reference
     * @param p             Hovorka parameters
     * @param carbMmolNow   carbs delivered at this minute [mmol] — impulse input to Qsto1
     * @param insulinEffect ISF × VG × iobActivityRate [mmol/min]
     * @return next state after 1-minute integration
     */
    public HovorkaState step(
            HovorkaState state,
            HovorkaParameters p,
            double carbMmolNow,
            double insulinEffect) {

        // Carbs are an impulse input: add to Qsto1 and update the reference meal dose.
        HovorkaState s0 = carbMmolNow > 0
                ? new HovorkaState(
                        state.q1(), state.q2(),
                        state.qsto1() + carbMmolNow,
                        state.qsto2(), state.qgut(), state.inc(),
                        state.mealMmol() + carbMmolNow)
                : state;

        double mealMmol = s0.mealMmol();   // constant throughout this RK4 step

        double[] y  = toArray(s0);
        double[] k1 = derivatives(y,                   p, mealMmol, insulinEffect);
        double[] k2 = derivatives(add(y, scale(k1, 0.5)), p, mealMmol, insulinEffect);
        double[] k3 = derivatives(add(y, scale(k2, 0.5)), p, mealMmol, insulinEffect);
        double[] k4 = derivatives(add(y, k3),             p, mealMmol, insulinEffect);

        double[] yn = new double[6];
        for (int i = 0; i < 6; i++) {
            yn[i] = y[i] + (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]) / 6.0;
        }
        return fromArray(yn, mealMmol).clampNonNegative();
    }

    /**
     * Compute the 6 ODE derivatives.
     *
     * <p>y[0]=Q1, y[1]=Q2, y[2]=Qsto1, y[3]=Qsto2, y[4]=Qgut, y[5]=Inc</p>
     *
     * @param y             current state as double array (6 elements)
     * @param p             Hovorka parameters
     * @param mealMmol      reference meal dose [mmol] for k_empt (constant per step)
     * @param insulinEffect glucose removal rate from bolus insulin [mmol/min]
     */
    double[] derivatives(double[] y, HovorkaParameters p,
                         double mealMmol, double insulinEffect) {

        double q1    = Math.max(0.0, y[0]);
        double q2    = Math.max(0.0, y[1]);
        double qsto1 = Math.max(0.0, y[2]);
        double qsto2 = Math.max(0.0, y[3]);
        double qgut  = Math.max(0.0, y[4]);
        double inc   = Math.max(0.0, y[5]);

        double g    = p.glucoseClamped(q1);
        double f01c = p.f01clamped(g);

        double qsto  = qsto1 + qsto2;
        double kempt = gutModel.kEmpt(qsto, mealMmol);

        // Scale K_ABS by the macro-modulated gastric-emptying time (Gap-1 fix).
        // A high-fat/protein meal has a longer tMaxG → slower intestinal drain
        // → Ra peak shifts right without changing total absorbed glucose.
        double kAbsEff = DallaManGutModel.effectiveKAbs(p.tMaxG());
        double ra    = gutModel.ra(qgut, kAbsEff);

        // Glucose compartments
        double dq1 = -f01c - p.k12() * q1 + p.k21() * q2
                   + ra + p.egpNet() - insulinEffect
                   - ALPHA_INC * inc * q1;
        double dq2 = p.k12() * q1 - p.k21() * q2;

        // Dalla Man gut compartments
        double dqsto1 = -DallaManGutModel.K_GRI * qsto1;
        double dqsto2 = DallaManGutModel.K_GRI * qsto1 - kempt * qsto2;
        double dqgut  = kempt * qsto2 - kAbsEff * qgut;

        // Incretin GLP-1
        double dinc = K_INC * ra - K_DEL * inc;

        return new double[]{dq1, dq2, dqsto1, dqsto2, dqgut, dinc};
    }

    // ── Array helpers ─────────────────────────────────────────────────────────

    private double[] toArray(HovorkaState s) {
        return new double[]{s.q1(), s.q2(), s.qsto1(), s.qsto2(), s.qgut(), s.inc()};
    }

    private HovorkaState fromArray(double[] y, double mealMmol) {
        return new HovorkaState(y[0], y[1], y[2], y[3], y[4], y[5], mealMmol);
    }

    private double[] add(double[] a, double[] b) {
        double[] r = new double[6];
        for (int i = 0; i < 6; i++) r[i] = a[i] + b[i];
        return r;
    }

    private double[] scale(double[] a, double s) {
        double[] r = new double[6];
        for (int i = 0; i < 6; i++) r[i] = a[i] * s;
        return r;
    }
}
