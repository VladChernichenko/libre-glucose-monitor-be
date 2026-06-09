package che.glucosemonitorbe.hovorka;

import org.springframework.stereotype.Component;

/**
 * RK4 numerical integrator for the Hovorka 2-compartment glucose model.
 *
 * <h3>ODE System (per minute, all quantities in mmol or mmol/min)</h3>
 * <pre>
 *   G      = Q1 / VG
 *   F01_c  = f01 * min(1, G / 4.5)         — non-insulin-dependent utilisation
 *   Ra     = D2 / tMaxG                     — glucose appearance from gut
 *
 *   dQ1/dt = -F01_c - k12*Q1 + k21*Q2 + Ra + egpNet - insulinEffect
 *   dQ2/dt =  k12*Q1 - k21*Q2
 *   dD1/dt =  aG * carbMmolPerMin - D1/tMaxG
 *   dD2/dt =  D1/tMaxG - D2/tMaxG
 * </pre>
 *
 * <p>Integration step: 1 minute (matches CGM resolution and is physiologically adequate).</p>
 *
 * <p>insulinEffect [mmol/min] = ISF * VG * iobActivityRate [units/min]
 * where iobActivityRate = max(0, IOB(t) - IOB(t+1)) is the per-minute insulin work rate,
 * computed externally from the OpenAPS exponential IOB curve.</p>
 */
@Component
public class HovorkaOdeSolver {

    /**
     * Advance the state by exactly one minute using the classical RK4 method.
     *
     * @param state          current state vector [Q1, Q2, D1, D2]
     * @param p              Hovorka parameters
     * @param carbMmolNow    grams of carbs eaten at this minute * aG / 0.18  [mmol] — bolus input to D1
     * @param insulinEffect  ISF * VG * iobActivityRate [mmol/min] — glucose removed by bolus insulin
     * @return               next state after 1-minute integration
     */
    public HovorkaState step(
            HovorkaState state,
            HovorkaParameters p,
            double carbMmolNow,
            double insulinEffect) {

        // Carbs are an impulse input at t=0 of this minute — added to D1 before integration.
        // This models the meal as an instantaneous delivery to the "stomach" compartment.
        HovorkaState s0 = new HovorkaState(
                state.q1(),
                state.q2(),
                state.d1() + carbMmolNow,
                state.d2()
        );

        // RK4 — h = 1 min
        double[] y = toArray(s0);
        double[] k1 = derivatives(y, p, insulinEffect);
        double[] k2 = derivatives(add(y, scale(k1, 0.5)), p, insulinEffect);
        double[] k3 = derivatives(add(y, scale(k2, 0.5)), p, insulinEffect);
        double[] k4 = derivatives(add(y, k3),             p, insulinEffect);

        double[] yn = new double[4];
        for (int i = 0; i < 4; i++) {
            yn[i] = y[i] + (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]) / 6.0;
        }
        return fromArray(yn).clampNonNegative();
    }

    /**
     * Compute the 4 derivatives of the Hovorka ODE system.
     *
     * <p>y[0] = Q1, y[1] = Q2, y[2] = D1, y[3] = D2</p>
     *
     * @param y              current state as double array
     * @param p              parameters
     * @param insulinEffect  glucose removal rate from bolus insulin [mmol/min]
     */
    double[] derivatives(double[] y, HovorkaParameters p, double insulinEffect) {
        double q1 = Math.max(0.0, y[0]);
        double q2 = Math.max(0.0, y[1]);
        double d1 = Math.max(0.0, y[2]);
        double d2 = Math.max(0.0, y[3]);

        double g    = p.glucoseClamped(q1);         // mmol/L
        double f01c = p.f01clamped(g);              // mmol/min — non-insulin-dependent utilisation
        double ra   = d2 / p.tMaxG();               // mmol/min — gut glucose appearance

        // Q1: central glucose compartment
        // Gains: intercompartmental inflow (k21*Q2) + gut absorption (Ra) + hepatic production (egpNet)
        // Losses: non-insulin utilisation (F01_c) + transfer out (k12*Q1) + insulin effect
        double dq1 = -f01c - p.k12() * q1 + p.k21() * q2 + ra + p.egpNet() - insulinEffect;

        // Q2: peripheral glucose compartment
        double dq2 = p.k12() * q1 - p.k21() * q2;

        // D1: stomach compartment — drains to D2 at rate 1/tMaxG
        // carbMmolNow was already added to D1 before calling derivatives()
        double dd1 = -d1 / p.tMaxG();

        // D2: intestine compartment — receives from D1, drains to bloodstream (Ra = D2/tMaxG)
        double dd2 = d1 / p.tMaxG() - d2 / p.tMaxG();

        return new double[]{dq1, dq2, dd1, dd2};
    }

    // ── Array helpers ─────────────────────────────────────────────────────────

    private double[] toArray(HovorkaState s) {
        return new double[]{s.q1(), s.q2(), s.d1(), s.d2()};
    }

    private HovorkaState fromArray(double[] y) {
        return new HovorkaState(y[0], y[1], y[2], y[3]);
    }

    private double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]};
    }

    private double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s, a[3] * s};
    }
}
