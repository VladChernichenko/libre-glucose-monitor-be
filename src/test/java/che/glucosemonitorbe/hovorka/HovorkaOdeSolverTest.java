package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the Hovorka ODE solver.
 *
 * Each test validates one physiological property of the model:
 * 1. Steady state — glucose stays stable with no inputs and EGP = F01.
 * 2. Glucose drop from correction bolus matches ISF (± tolerance).
 * 3. Glucose rise from meal (no insulin) matches CR.
 * 4. Gut absorption starts at zero — no instantaneous glucose spike.
 * 5. Non-negativity — state variables never go below zero.
 * 6. Hypoglycaemia buffer — F01_c is clamped below 4.5 mmol/L → glucose stabilises.
 */
class HovorkaOdeSolverTest {

    private HovorkaOdeSolver solver;
    private HovorkaParameters params;

    /** Standard 70 kg patient with ISF=2.2, CR=2.0. */
    @BeforeEach
    void setUp() {
        solver = new HovorkaOdeSolver();
        double weight = 70.0;
        double vG     = HovorkaParameters.VG_PER_KG * weight;   // 11.2 L
        double f01    = HovorkaParameters.F01_PER_KG * weight;  // 0.679 mmol/min
        params = new HovorkaParameters(
                vG, f01, f01,                                    // egpNet = f01 (SS identity)
                HovorkaParameters.K12_POP,
                HovorkaParameters.K21_POP,
                45.0 / 1.68,                                     // tMaxG ≈ 26.8 min
                0.80,                                            // A_G
                2.2,                                             // ISF [mmol/L/unit]
                weight
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Steady state — glucose stays stable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void steadyState_noInputs_glucoseIsStable() {
        double g0 = 5.5;
        HovorkaState state = HovorkaState.steadyState(g0, params);

        // Advance 120 min with no inputs
        for (int m = 0; m < 120; m++) {
            state = solver.step(state, params, 0.0, 0.0);
        }

        double gFinal = state.glucoseMmolL(params);
        // Glucose should stay within ±0.1 mmol/L of the starting value
        assertThat(gFinal).isCloseTo(g0, within(0.10));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: 1-unit correction bolus → glucose drops by ≈ ISF over 4.5 h DIA
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void correctionBolus_1Unit_glucoseDropMatchesIsf() {
        double g0     = 10.0;   // starting at hyperglycaemia
        double isf    = params.isf();     // 2.2 mmol/L per unit
        double diaMin = 4.5 * 60;        // 270 min (Fiasp DIA)
        double peakMin = 55.0;

        HovorkaState state = HovorkaState.steadyState(g0, params);

        // Simulate IOB activity rate from a 1-unit bolus using OpenAPS formula.
        // effectiveInsulinVolume() = 2×VG accounts for 2-compartment distribution factor.
        double totalEffect = 0.0;
        for (int m = 1; m <= (int) diaMin; m++) {
            double iobNow  = iobExponential(1.0, m,     diaMin, peakMin);
            double iobNext = iobExponential(1.0, m + 1, diaMin, peakMin);
            double actRate = Math.max(0.0, iobNow - iobNext);  // units/min
            double insulinEffect = isf * params.effectiveInsulinVolume() * actRate; // mmol/min
            state = solver.step(state, params, 0.0, insulinEffect);
            totalEffect += insulinEffect;
        }

        double gFinal = state.glucoseMmolL(params);
        double actualDrop = g0 - gFinal;

        // The glucose drop should match ISF within ±25% (model is approximate)
        assertThat(actualDrop).isBetween(isf * 0.75, isf * 1.25);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: 20 g meal (no insulin) → glucose rises, peak ~ CR × grams/10
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void meal20g_noInsulin_glucoseRisesApproximateToCr() {
        double g0     = 5.5;
        double carbs  = 20.0;     // grams
        double cr     = 2.0;      // mmol/L per 10 g
        double expectedRise = cr * (carbs / 10.0);  // 4.0 mmol/L

        HovorkaState state = HovorkaState.steadyState(g0, params);

        // Deliver meal at minute 0, then simulate 3 h
        double carbMmol = carbs * params.aG() / 0.18;
        double peakGlucose = g0;
        for (int m = 1; m <= 180; m++) {
            double mealInput = (m == 1) ? carbMmol : 0.0;
            state = solver.step(state, params, mealInput, 0.0);
            peakGlucose = Math.max(peakGlucose, state.glucoseMmolL(params));
        }

        double actualRise = peakGlucose - g0;
        // Rise should be within ±40% of expected (no insulin, pure carb test)
        assertThat(actualRise).isBetween(expectedRise * 0.60, expectedRise * 1.40);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Gut absorption — Ra(t=0) = 0  (no instantaneous spike)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void gutAbsorption_raIsZeroAtMealTime_noInstantaneousSpike() {
        // Before the meal, glucose compartment should not jump immediately
        HovorkaState before = HovorkaState.steadyState(5.5, params);
        double gBefore = before.glucoseMmolL(params);

        // Deliver a large meal
        double carbMmol = 50.0 * params.aG() / 0.18;
        HovorkaState after1Min = solver.step(before, params, carbMmol, 0.0);

        double gAfter1Min = after1Min.glucoseMmolL(params);
        // After 1 min, blood glucose should have risen by at most 0.2 mmol/L.
        // The Hovorka gut chain: D1→D2 transfers fast (rate 1/tMaxG per min), but Ra = D2/tMaxG
        // means glucose appearance is still small in the first minute (double delay).
        assertThat(gAfter1Min - gBefore).isLessThan(0.20);

        // D1 should now have carbs (stomach received the meal)
        assertThat(after1Min.d1()).isGreaterThan(0.0);
        // D2 builds up from D1 at rate D1/tMaxG ≈ 3.7%/min → after 1 min D2 ≈ 3-4% of D1.
        // This is the correct Hovorka 2-compartment behaviour (not an instantaneous spike).
        assertThat(after1Min.d2()).isGreaterThan(0.0);      // D2 starts accumulating immediately
        assertThat(after1Min.d2()).isLessThan(after1Min.d1()); // but stays below D1
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: Non-negativity — state variables never go below zero
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void integration_stateVariablesAlwaysNonNegative() {
        HovorkaState state = HovorkaState.steadyState(3.5, params); // near-hypo

        for (int m = 0; m < 240; m++) {
            // Aggressive insulin effect to stress the solver
            double insulinEffect = params.isf() * params.vG() * 0.01;
            state = solver.step(state, params, 0.0, insulinEffect);

            assertThat(state.q1()).isGreaterThanOrEqualTo(0.0);
            assertThat(state.q2()).isGreaterThanOrEqualTo(0.0);
            assertThat(state.d1()).isGreaterThanOrEqualTo(0.0);
            assertThat(state.d2()).isGreaterThanOrEqualTo(0.0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6: Hypoglycaemia buffer — glucose stabilises below 4.5 mmol/L
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void hypoglycaemia_f01cClamp_preventsFurtherGlucoseDrop() {
        // Start at 3.0 mmol/L with no insulin
        HovorkaState state = HovorkaState.steadyState(3.0, params);

        double minGlucose = 3.0;
        for (int m = 0; m < 120; m++) {
            state = solver.step(state, params, 0.0, 0.0);
            minGlucose = Math.min(minGlucose, state.glucoseMmolL(params));
        }

        // Without insulin, glucose should rise from 3.0 because EGP_net > F01_c at low glucose
        assertThat(state.glucoseMmolL(params)).isGreaterThanOrEqualTo(3.0);
        assertThat(minGlucose).isGreaterThanOrEqualTo(1.0); // never goes below model floor
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7: BasalInsulinResolver — suppression curve boundaries
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void basalResolver_suppressionCurve_followsExpectedProfile() {
        BasalInsulinResolver resolver = new BasalInsulinResolver();

        assertThat(resolver.suppressionCurve(0.0)).isCloseTo(0.0, within(0.001));
        assertThat(resolver.suppressionCurve(1.0))
                .isCloseTo(BasalInsulinResolver.PEAK_X3_BASAL * 0.5, within(0.01));
        assertThat(resolver.suppressionCurve(5.0))
                .isCloseTo(BasalInsulinResolver.PEAK_X3_BASAL, within(0.001));
        assertThat(resolver.suppressionCurve(20.0))
                .isCloseTo(BasalInsulinResolver.PEAK_X3_BASAL, within(0.001));
        assertThat(resolver.suppressionCurve(28.0)).isCloseTo(0.0, within(0.001));
        assertThat(resolver.suppressionCurve(35.0)).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 8: ODE derivatives — glucose SS gives zero rate of change
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void derivatives_atSteadyState_allZero() {
        HovorkaState ss = HovorkaState.steadyState(5.5, params);
        double[] y = new double[]{ss.q1(), ss.q2(), ss.d1(), ss.d2()};

        // At SS with no inputs: egpNet = F01 → dQ1 = -F01 - k12*Q1 + k21*Q2 + EGP = 0
        // (k12=k21, Q1=Q2 at SS)
        double[] dy = solver.derivatives(y, params, 0.0);

        assertThat(dy[0]).isCloseTo(0.0, within(1e-6)); // dQ1/dt ≈ 0
        assertThat(dy[1]).isCloseTo(0.0, within(1e-6)); // dQ2/dt ≈ 0
        assertThat(dy[2]).isCloseTo(0.0, within(1e-6)); // dD1/dt = 0 (D1=0)
        assertThat(dy[3]).isCloseTo(0.0, within(1e-6)); // dD2/dt = 0 (D2=0)
    }

    // ── Helper: OpenAPS IOB (same formula as InsulinCalculatorService) ────────

    private static double iobExponential(double units, double minsAgo, double diaMin, double peak) {
        if (minsAgo < 0 || minsAgo >= diaMin || units <= 0) return 0.0;
        double denom = 1.0 - 2.0 * peak / diaMin;
        if (Math.abs(denom) < 1e-5) return units * Math.max(0.0, 1.0 - minsAgo / diaMin);
        double tau = peak * (1.0 - peak / diaMin) / denom;
        double a   = 2.0 * tau / diaMin;
        double s   = 1.0 / (1.0 - a + (1.0 + a) * Math.exp(-diaMin / tau));
        double bracket = (Math.pow(minsAgo, 2) / (tau * diaMin * (1.0 - a))
                - minsAgo / tau - 1.0) * Math.exp(-minsAgo / tau) + 1.0;
        double iob = units * (1.0 - s * (1.0 - a) * bracket);
        return Math.max(0.0, Math.min(units, iob));
    }
}
