package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the Hovorka ODE solver with Dalla Man 3-compartment gut model.
 *
 * 1. Steady state — glucose stays stable with no inputs and EGP = F01.
 * 2. Glucose drop from correction bolus matches ISF (± tolerance).
 * 3. Glucose rise from meal (no insulin) is within physiological range.
 * 4. Gut absorption — no instantaneous spike (Qsto1/Qsto2 build up correctly).
 * 5. Non-negativity — state variables never go below zero.
 * 6. Hypoglycaemia buffer — F01_c is clamped below 4.5 mmol/L.
 * 7. BasalInsulinResolver — suppression curve boundaries.
 * 8. ODE derivatives — glucose SS gives zero rate of change.
 */
class HovorkaOdeSolverTest {

    private HovorkaOdeSolver solver;
    private HovorkaParameters params;

    /** Standard 70 kg patient with ISF=2.2, CR=2.0. */
    @BeforeEach
    void setUp() {
        solver = new HovorkaOdeSolver(new DallaManGutModel());
        double weight = 70.0;
        double vG     = HovorkaParameters.VG_PER_KG * weight;   // 11.2 L
        double f01    = HovorkaParameters.F01_PER_KG * weight;  // 0.679 mmol/min
        params = new HovorkaParameters(
                vG, f01, f01,                                    // egpNet = f01 (SS identity)
                HovorkaParameters.K12_POP,
                HovorkaParameters.K21_POP,
                45.0 / 1.68,                                     // tMaxG ≈ 26.8 min (kept for params)
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

        for (int m = 0; m < 120; m++) {
            state = solver.step(state, params, 0.0, 0.0);
        }

        assertThat(state.glucoseMmolL(params)).isCloseTo(g0, within(0.10));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: 1-unit correction bolus → glucose drops by ≈ ISF over 4.5 h DIA
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void correctionBolus_1Unit_glucoseDropMatchesIsf() {
        double g0     = 10.0;
        double isf    = params.isf();
        double diaMin = 4.5 * 60;
        double peakMin = 55.0;

        HovorkaState state = HovorkaState.steadyState(g0, params);

        for (int m = 1; m <= (int) diaMin; m++) {
            double iobNow  = iobExponential(1.0, m,     diaMin, peakMin);
            double iobNext = iobExponential(1.0, m + 1, diaMin, peakMin);
            double actRate = Math.max(0.0, iobNow - iobNext);
            double insulinEffect = isf * params.effectiveInsulinVolume() * actRate;
            state = solver.step(state, params, 0.0, insulinEffect);
        }

        double gFinal = state.glucoseMmolL(params);
        double actualDrop = g0 - gFinal;

        assertThat(actualDrop).isBetween(isf * 0.75, isf * 1.25);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: 20 g meal (no insulin) → glucose rises within physiological range
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void meal20g_noInsulin_glucoseRisesWithinPhysiologicalRange() {
        double g0    = 5.5;
        double carbs = 20.0;

        HovorkaState state = HovorkaState.steadyState(g0, params);

        double carbMmol = carbs * params.aG() / 0.18;
        double peakGlucose = g0;
        // 4-hour window to capture Dalla Man absorption peak (slower than linear D1/D2)
        for (int m = 1; m <= 240; m++) {
            double mealInput = (m == 1) ? carbMmol : 0.0;
            state = solver.step(state, params, mealInput, 0.0);
            peakGlucose = Math.max(peakGlucose, state.glucoseMmolL(params));
        }

        double actualRise = peakGlucose - g0;
        assertThat(actualRise).isBetween(1.5, 6.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Gut absorption — no instantaneous spike on meal delivery
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void gutAbsorption_raIsZeroAtMealTime_noInstantaneousSpike() {
        HovorkaState before = HovorkaState.steadyState(5.5, params);
        double gBefore = before.glucoseMmolL(params);

        double carbMmol = 50.0 * params.aG() / 0.18;
        HovorkaState after1Min = solver.step(before, params, carbMmol, 0.0);

        // Dalla Man: Ra starts from 0 → no immediate glucose spike
        assertThat(after1Min.glucoseMmolL(params) - gBefore).isLessThan(0.20);

        // Qsto1 has the meal; Qsto2 starts accumulating via K_GRI
        assertThat(after1Min.qsto1()).isGreaterThan(0.0);
        assertThat(after1Min.qsto2()).isGreaterThan(0.0);
        assertThat(after1Min.qsto2()).isLessThan(after1Min.qsto1());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: Non-negativity — state variables never go below zero
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void integration_stateVariablesAlwaysNonNegative() {
        HovorkaState state = HovorkaState.steadyState(3.5, params);

        for (int m = 0; m < 240; m++) {
            double insulinEffect = params.isf() * params.vG() * 0.01;
            state = solver.step(state, params, 0.0, insulinEffect);

            assertThat(state.q1()).isGreaterThanOrEqualTo(0.0);
            assertThat(state.q2()).isGreaterThanOrEqualTo(0.0);
            assertThat(state.qsto1()).isGreaterThanOrEqualTo(0.0);
            assertThat(state.qsto2()).isGreaterThanOrEqualTo(0.0);
            assertThat(state.qgut()).isGreaterThanOrEqualTo(0.0);
            assertThat(state.inc()).isGreaterThanOrEqualTo(0.0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6: Hypoglycaemia buffer — glucose stabilises below 4.5 mmol/L
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void hypoglycaemia_f01cClamp_preventsFurtherGlucoseDrop() {
        HovorkaState state = HovorkaState.steadyState(3.0, params);

        double minGlucose = 3.0;
        for (int m = 0; m < 120; m++) {
            state = solver.step(state, params, 0.0, 0.0);
            minGlucose = Math.min(minGlucose, state.glucoseMmolL(params));
        }

        assertThat(state.glucoseMmolL(params)).isGreaterThanOrEqualTo(3.0);
        assertThat(minGlucose).isGreaterThanOrEqualTo(1.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7: BasalInsulinResolver — suppression curve boundaries
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void basalResolver_suppressionCurve_followsExpectedProfile() {
        BasalInsulinResolver resolver = new BasalInsulinResolver();

        assertThat(resolver.suppressionCurve(0.0))
                .isCloseTo(BasalInsulinResolver.PEAK_X3_BASAL, within(0.001));
        assertThat(resolver.suppressionCurve(1.0))
                .isCloseTo(BasalInsulinResolver.PEAK_X3_BASAL, within(0.001));
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
        double[] y = new double[]{ss.q1(), ss.q2(), ss.qsto1(), ss.qsto2(), ss.qgut(), ss.inc()};

        double[] dy = solver.derivatives(y, params, 0.0, 0.0);

        assertThat(dy[0]).isCloseTo(0.0, within(1e-6)); // dQ1/dt ≈ 0
        assertThat(dy[1]).isCloseTo(0.0, within(1e-6)); // dQ2/dt ≈ 0
        assertThat(dy[2]).isCloseTo(0.0, within(1e-6)); // dQsto1/dt = 0 (Qsto1=0)
        assertThat(dy[3]).isCloseTo(0.0, within(1e-6)); // dQsto2/dt = 0
        assertThat(dy[4]).isCloseTo(0.0, within(1e-6)); // dQgut/dt  = 0
        assertThat(dy[5]).isCloseTo(0.0, within(1e-6)); // dInc/dt   = 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 9: Physics documentation — uncompensated EGP causes glucose rise
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void noCobNoIob_uncompensatedEgp_glucoseRisesOver4h() {
        // When egpNet = EGP0 (full hepatic production, no basal suppression) the ODE
        // correctly drives glucose up even without food or insulin. This test documents
        // the physics the prediction service must guard against.
        double g0   = 7.2;
        double egp0 = HovorkaParameters.EGP0_PER_KG * params.weightKg(); // 0.0161 * 70
        HovorkaParameters unstable = new HovorkaParameters(
                params.vG(), params.f01(), egp0,   // egpNet = full EGP0 > f01
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                params.tMaxG(), params.aG(), params.isf(), params.weightKg());

        HovorkaState state = HovorkaState.steadyState(g0, unstable);
        for (int m = 0; m < 240; m++) {
            state = solver.step(state, unstable, 0.0, 0.0);
        }

        double rise = state.glucoseMmolL(unstable) - g0;
        // EGP0 - f01 = (0.0161 - 0.0097) * 70 = 0.448 mmol/min → ~7 mmol rise in 4h (clamped)
        assertThat(rise).isGreaterThan(3.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 10: Steady-state EGP — glucose is stable at any euglycaemic level
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void noCobNoIob_steadyStateEgpEqualsF01_glucoseRemainsFlat() {
        // When egpNet = f01 (basal at steady state), glucose must stay flat.
        // This is the CORRECT behaviour the prediction service must produce.
        double g0 = 7.2;
        // params already has egpNet = f01 (set in setUp() as the SS identity)
        HovorkaState state = HovorkaState.steadyState(g0, params);
        for (int m = 0; m < 240; m++) {
            state = solver.step(state, params, 0.0, 0.0);
        }
        assertThat(state.glucoseMmolL(params)).isCloseTo(g0, within(0.15));
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
