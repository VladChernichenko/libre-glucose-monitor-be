package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the Hovorka ODE solver with Dalla Man 3-compartment gut model.
 *
 * 1. Steady state - glucose stays stable with no inputs and EGP = F01.
 * 2. Glucose drop from correction bolus matches ISF (± tolerance).
 * 3. Glucose rise from meal (no insulin) is within physiological range.
 * 4. Gut absorption - no instantaneous spike (Qsto1/Qsto2 build up correctly).
 * 5. Non-negativity - state variables never go below zero.
 * 6. Hypoglycaemia buffer - F01_c is clamped below 4.5 mmol/L.
 * 7. BasalInsulinResolver - suppression curve boundaries.
 * 8. ODE derivatives - glucose SS gives zero rate of change.
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
        // For unit-test stability: set egp0 = f01 so that EGP(x3=0) = egp0 = f01 = egpNet.
        // This avoids needing continuous basal insulin to maintain x3; x3_ss = 0 at this configuration.
        // The population egp0 (EGP0_PER_KG * weight = 1.127) is tested separately in
        // noCobNoIob_uncompensatedEgp_glucoseRisesOver4h and the new x3 dynamics tests.
        double egp0   = f01;  // egp0 = egpNet for stable no-insulin tests
        params = new HovorkaParameters(
                vG, f01, f01,                                    // egpNet = f01 (SS identity)
                egp0,                                            // egp0 = f01 → x3_ss = 0, EGP(0) = f01
                HovorkaParameters.K12_POP,
                HovorkaParameters.K21_POP,
                45.0 / 1.68,                                     // tMaxG ≈ 26.8 min (kept for params)
                0.80,                                            // A_G
                2.2,                                             // ISF [mmol/L/unit]
                weight
        );
    }

    // ---
    // Test 1: Steady state - glucose stays stable
    // ---

    @Test
    void steadyState_noInputs_glucoseIsStable() {
        double g0 = 5.5;
        // Warm-start x3 so EGP(ss) = egp0*(1-x3_ss) = egpNet = f01
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState state = HovorkaState.steadyState(g0, params).withX3(x3ss);

        for (int m = 0; m < 120; m++) {
            state = solver.step(state, params, 0.0, 0.0);
        }

        assertThat(state.glucoseMmolL(params)).isCloseTo(g0, within(0.10));
    }

    // ---
    // Test 2: 1-unit correction bolus -> glucose drops by ≈ ISF over 4.5 h DIA
    // ---

    @Test
    void correctionBolus_1Unit_glucoseDropMatchesIsf() {
        double g0     = 10.0;
        double isf    = params.isf();
        double diaMin = 4.5 * 60;
        double peakMin = 55.0;

        // Warm-start x3 so basal EGP(ss) = egpNet = f01 (no drift without bolus)
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState state = HovorkaState.steadyState(g0, params).withX3(x3ss);

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

    // ---
    // Test 3: 20 g meal (no insulin) -> glucose rises within physiological range
    // ---

    @Test
    void meal20g_noInsulin_glucoseRisesWithinPhysiologicalRange() {
        double g0    = 5.5;
        double carbs = 20.0;

        // Warm-start x3 so basal EGP is stable (egpNet = f01 at SS)
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState state = HovorkaState.steadyState(g0, params).withX3(x3ss);

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

    // ---
    // Test 4: Gut absorption - no instantaneous spike on meal delivery
    // ---

    @Test
    void gutAbsorption_raIsZeroAtMealTime_noInstantaneousSpike() {
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState before = HovorkaState.steadyState(5.5, params).withX3(x3ss);
        double gBefore = before.glucoseMmolL(params);

        double carbMmol = 50.0 * params.aG() / 0.18;
        HovorkaState after1Min = solver.step(before, params, carbMmol, 0.0);

        // Dalla Man: Ra starts from 0 -> no immediate glucose spike
        assertThat(after1Min.glucoseMmolL(params) - gBefore).isLessThan(0.20);

        // Qsto1 has the meal; Qsto2 starts accumulating via K_GRI
        assertThat(after1Min.qsto1()).isGreaterThan(0.0);
        assertThat(after1Min.qsto2()).isGreaterThan(0.0);
        assertThat(after1Min.qsto2()).isLessThan(after1Min.qsto1());
    }

    // ---
    // Test 5: Non-negativity - state variables never go below zero
    // ---

    @Test
    void integration_stateVariablesAlwaysNonNegative() {
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState state = HovorkaState.steadyState(3.5, params).withX3(x3ss);

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

    // ---
    // Test 6: Hypoglycaemia buffer - glucose stabilises below 4.5 mmol/L
    // ---

    @Test
    void hypoglycaemia_f01cClamp_preventsFurtherGlucoseDrop() {
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState state = HovorkaState.steadyState(3.0, params).withX3(x3ss);

        double minGlucose = 3.0;
        for (int m = 0; m < 120; m++) {
            state = solver.step(state, params, 0.0, 0.0);
            minGlucose = Math.min(minGlucose, state.glucoseMmolL(params));
        }

        assertThat(state.glucoseMmolL(params)).isGreaterThanOrEqualTo(3.0);
        assertThat(minGlucose).isGreaterThanOrEqualTo(1.0);
    }

    // ---
    // Test 7: BasalInsulinResolver - suppression curve boundaries
    // ---

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

    // ---
    // Test 8: ODE derivatives - glucose SS gives zero rate of change
    // ---

    @Test
    void derivatives_atSteadyState_allZero() {
        // With dynamic EGP, x3 must be at basal steady state so EGP(ss) = egpNet = f01.
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState ss = HovorkaState.steadyState(5.5, params).withX3(x3ss);
        double[] y = new double[]{ss.q1(), ss.q2(), ss.qsto1(), ss.qsto2(), ss.qgut(), ss.inc(),
                                   ss.x3(), ss.protFatGut()};

        double[] dy = solver.derivatives(y, params, 0.0, 0.0);

        assertThat(dy[0]).isCloseTo(0.0, within(1e-6)); // dQ1/dt ≈ 0
        assertThat(dy[1]).isCloseTo(0.0, within(1e-6)); // dQ2/dt ≈ 0
        assertThat(dy[2]).isCloseTo(0.0, within(1e-6)); // dQsto1/dt = 0 (Qsto1=0)
        assertThat(dy[3]).isCloseTo(0.0, within(1e-6)); // dQsto2/dt = 0
        assertThat(dy[4]).isCloseTo(0.0, within(1e-6)); // dQgut/dt  = 0
        assertThat(dy[5]).isCloseTo(0.0, within(1e-6)); // dInc/dt   = 0
    }

    // ---
    // Test 9: Physics documentation - uncompensated EGP causes glucose rise
    // ---

    @Test
    void noCobNoIob_uncompensatedEgp_glucoseRisesOver4h() {
        // When egpNet = EGP0 (full hepatic production, no basal suppression) the ODE
        // correctly drives glucose up even without food or insulin. This test documents
        // the physics the prediction service must guard against.
        double g0   = 7.2;
        double egp0 = HovorkaParameters.EGP0_PER_KG * params.weightKg(); // 0.0161 * 70
        HovorkaParameters unstable = new HovorkaParameters(
                params.vG(), params.f01(), egp0, egp0,  // egpNet = egp0 = full EGP0 > f01
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                params.tMaxG(), params.aG(), params.isf(), params.weightKg());

        HovorkaState state = HovorkaState.steadyState(g0, unstable);
        for (int m = 0; m < 240; m++) {
            state = solver.step(state, unstable, 0.0, 0.0);
        }

        double rise = state.glucoseMmolL(unstable) - g0;
        // EGP0 - f01 = (0.0161 - 0.0097) * 70 = 0.448 mmol/min -> ~7 mmol rise in 4h (clamped)
        assertThat(rise).isGreaterThan(3.0);
    }

    // ---
    // Test 10: Steady-state EGP - glucose is stable at any euglycaemic level
    // ---

    @Test
    void noCobNoIob_steadyStateEgpEqualsF01_glucoseRemainsFlat() {
        // When egpNet = f01 (basal at steady state), glucose must stay flat.
        // This is the CORRECT behaviour the prediction service must produce.
        // With dynamic EGP, x3 must be warm-started so EGP(ss) = egp0*(1-x3_ss) = egpNet = f01.
        double g0 = 7.2;
        // params already has egpNet = f01 (set in setUp() as the SS identity)
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState state = HovorkaState.steadyState(g0, params).withX3(x3ss);
        for (int m = 0; m < 240; m++) {
            state = solver.step(state, params, 0.0, 0.0);
        }
        assertThat(state.glucoseMmolL(params)).isCloseTo(g0, within(0.15));
    }

    // ---
    // Test 11: Renal clearance - inactive below 9 mmol/L, active above
    // ---

    @Test
    void renalClearance_activatesAboveThreshold_inactiveBelow() {
        // At SS Q2 balance (k12*Q1 = k21*Q2), gut empty, egpNet=f01, x3=x3_ss:
        //   dq1 = -f01c - FR + EGP(x3_ss) = -f01 - FR + f01 = -FR
        // So at G=5.5: dq1 ≈ 0 (FR inactive); at G=12: dq1 = -FR < 0.
        double q2Ratio = HovorkaParameters.K12_POP / HovorkaParameters.K21_POP; // = 1.0
        // With dynamic EGP, set x3=x3_ss so EGP(x3_ss) = egpNet = f01
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());

        // Euglycemia (G=5.5 < KE2=9.0) - FR must not fire
        double q1Low = 5.5 * params.vG();
        double[] yLow = {q1Low, q2Ratio * q1Low, 0, 0, 0, 0, x3ss, 0};
        double[] dyLow = solver.derivatives(yLow, params, 0.0, 0.0);
        assertThat(dyLow[0]).isCloseTo(0.0, within(1e-4));

        // Hyperglycemia (G=12 > KE2=9.0) - FR fires, pulling Q1 down
        double q1High = 12.0 * params.vG();
        double[] yHigh = {q1High, q2Ratio * q1High, 0, 0, 0, 0, x3ss, 0};
        double[] dyHigh = solver.derivatives(yHigh, params, 0.0, 0.0);
        assertThat(dyHigh[0]).isLessThan(0.0);

        // Verify magnitude: FR = ke1 * (G - ke2) * VG
        double expectedFR = HovorkaOdeSolver.KE1 * (12.0 - HovorkaOdeSolver.KE2) * params.vG();
        assertThat(dyHigh[0]).isCloseTo(-expectedFR, within(1e-4));
    }

    // ---
    // Test 12: Ileal brake - elevated Inc reduces gastric emptying (saturating form)
    // ---

    @Test
    void glp1Inhibition_highInc_kemptNeverReachesZero() {
        // With old linear formula and PHI_GLP1=0.5, inc=2.0 would give 1-0.5*2=0 -> floor at 0.20
        // New saturating formula: 1/(1+2.0*2.0) = 0.20 naturally, no clamp needed
        // At inc=10: 1/(1+20) = 0.048 — strictly positive
        HovorkaState state = HovorkaState.steadyState(6.0, params);
        double mealMmol = 100.0;  // full stomach reference
        // Manually construct a state with high inc, Qsto1 empty, Qsto2 full (mid-emptying)
        // This isolates the draining effect without interference from Qsto1->Qsto2 flow
        HovorkaState highInc = new HovorkaState(
                state.q1(), state.q2(), 0.0, 50.0, 0.0, 10.0, 0.0, 0.0, mealMmol, 70);

        HovorkaState next = solver.step(highInc, params, 0.0, 0.0);
        // Qsto2 should drain (kemptEff > 0 always), not be frozen at the clamp floor
        assertThat(next.qsto2()).isLessThan(50.0);
        // With qsto=50 (half-full), kempt is reduced toward K_MIN; with phi ≈ 0.048
        // drain should be noticeable but not huge
        // Old form would have drained more (0.20 floor). New form is more gentle (~0.048).
        assertThat(50.0 - next.qsto2()).isLessThan(5.0);  // new form drains <5 mmol/min at high inc
    }

    @Test
    void glp1Inhibition_zeroInc_noEffect() {
        // When inc=0, phi=1/(1+0)=1.0 -> kemptEff = kempt (no change)
        HovorkaState state = HovorkaState.steadyState(6.0, params);
        double mealMmol = 100.0;  // full stomach reference
        // Isolate Qsto2 draining: Qsto1 empty, Qsto2 at half-full (50 mmol)
        // At qsto=50 with mealMmol=100, kempt is reduced toward K_MIN
        HovorkaState withStomach = new HovorkaState(
                state.q1(), state.q2(), 0.0, 100.0, 0.0, 0.0, 0.0, 0.0, mealMmol, 70);
        HovorkaState next = solver.step(withStomach, params, 0.0, 0.0);
        // With qsto=100 (full), kempt ≈ K_MAX * giScale; phi=1.0, activeGI=70 -> giScale=0.70
        // drain = 100 * 0.0558 * 0.70 ≈ 3.906 mmol/min
        double giScale = 70.0 / 100.0;  // default activeGI = 70
        assertThat(100.0 - next.qsto2()).isCloseTo(100.0 * DallaManGutModel.K_MAX * giScale, within(0.5));
    }

    // ---
    // Test 13: GLP-1 incretin accumulates during meal absorption
    // ---

    @Test
    void incGlp1_risesDuringMealAbsorption_peaksAboveBaseline() {
        // dInc/dt = K_INC_PF*ProtFatGut − K_DEL*Inc; ProtFatGut drains at K_PF_DRAIN.
        // Inc is now driven by protein+fat gut transit (NOT carb Ra — Gap 4 fix).
        // Inject 400 kcal protein+fat alongside the carb meal; Inc must peak above baseline.
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState state = HovorkaState.steadyState(5.5, params).withX3(x3ss);
        double carbMmol = 60.0 * params.aG() / 0.18;
        // 400 kcal protein+fat drives GLP-1; carbs alone leave Inc at 0 in the new model
        state = solver.step(state, params, carbMmol, 70, 400.0, 0.0, 0.0);

        double maxInc = 0.0;
        for (int m = 1; m <= 120; m++) {
            state = solver.step(state, params, 0.0, 70, 0.0, 0.0, 0.0);
            maxInc = Math.max(maxInc, state.inc());
        }

        assertThat(maxInc).isGreaterThan(0.005);
    }

    // ---
    // Test 14 (new): GLP-1 driver - protein+fat loaded, Inc rises and protFatGut drains
    // ---

    @Test
    void glp1Driver_protFatLoaded_incRises() {
        // Load 500 kcal protein+fat into protFatGut — Inc should rise over 60 min
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState s0 = HovorkaState.steadyState(5.5, params).withX3(x3ss);
        // Manually inject 500 kcal into protFatGut via the protFatKcalNow argument
        HovorkaState withPF = new HovorkaState(
                s0.q1(), s0.q2(), 0, 0, 0, 0.0, 0.0, 500.0, 0.0, 70);

        HovorkaState s60 = withPF;
        for (int i = 0; i < 60; i++) {
            s60 = solver.step(s60, params, 0.0, 70, 0.0, 0.0, 0.0);
        }
        assertThat(s60.inc()).isGreaterThan(0.1);        // Inc must have risen from protein/fat
        assertThat(s60.protFatGut()).isLessThan(500.0);  // protFatGut drains
    }

    @Test
    void glp1Driver_noProtFat_incStaysZero() {
        // Without protein/fat, Inc should stay at 0
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState s0 = HovorkaState.steadyState(5.5, params).withX3(x3ss);
        HovorkaState s60 = s0;
        for (int i = 0; i < 60; i++) {
            s60 = solver.step(s60, params, 0.0, 70, 0.0, 0.0, 0.0);
        }
        assertThat(s60.inc()).isLessThan(0.001);
    }

    // ---
    // Test 15 (renumbered): GI scaling - low GI absorbs slower than high GI
    // ---

    @Test
    void giScaling_lowGI_absorbsSlowerThanHighGI() {
        // Two identical 50g carb meals, GI=30 vs GI=100
        // After 60 min, high-GI should have less remaining in qsto2 and qgut
        double carbMmol = 50.0 / 0.18;  // ~277.8 mmol
        double x3ss = Math.max(0.0, 1.0 - params.egpNet() / params.egp0());
        HovorkaState ssHi = HovorkaState.steadyState(5.5, params).withX3(x3ss);
        HovorkaState ssLo = HovorkaState.steadyState(5.5, params).withX3(x3ss);

        HovorkaState stateHi = solver.step(ssHi, params, carbMmol, 100, 0.0, 0.0, 0.0);
        HovorkaState stateLo = solver.step(ssLo, params, carbMmol, 30,  0.0, 0.0, 0.0);

        for (int i = 0; i < 59; i++) {
            stateHi = solver.step(stateHi, params, 0.0, 100, 0.0, 0.0, 0.0);
            stateLo = solver.step(stateLo, params, 0.0, 30,  0.0, 0.0, 0.0);
        }
        // High GI empties the stomach (qsto1) faster
        assertThat(stateHi.qsto1()).isLessThan(stateLo.qsto1());
        // High GI raises blood glucose faster (more Ra delivered)
        assertThat(stateHi.q1()).isGreaterThan(stateLo.q1());
    }

    // ---
    // Task 7: x3 dynamics and dynamic EGP tests
    // ---

    @Test
    void x3Dynamics_largeBolus_raisesX3() {
        // Verify that a large insulin effect drives x3 upward from zero.
        // With the empirical bridge: plasmInsulin = insulinEffect/(isf*effVol)*V_I_SCALE
        // and dx3 = -KA3*x3 + KB3*plasmInsulin.
        // A sustained large bolus effect (5 U over 60 min) must produce measurable x3 > 0.
        HovorkaState s0 = HovorkaState.steadyState(10.0, params);
        // bigEffect: a realistic 5U correction spread over 60 min
        double bigEffect = 5.0 * params.isf() * params.effectiveInsulinVolume() / 60.0;
        HovorkaState s60 = s0;
        for (int i = 0; i < 60; i++) {
            s60 = solver.step(s60, params, 0.0, 70, 0.0, bigEffect, 0.0);
        }
        // x3 must be strictly positive and greater than zero start value
        assertThat(s60.x3()).isGreaterThan(0.0);
        // Glucose must have fallen (insulin effect)
        assertThat(s60.glucoseMmolL(params)).isLessThan(10.0);
    }

    @Test
    void x3Dynamics_noInsulin_x3StaysAtZero() {
        // Without any insulin, x3 has no drive (dx3 = -KA3*x3 + KB3*0 = 0 at x3=0).
        // Starting at zero, x3 must stay at zero.
        HovorkaState s0 = HovorkaState.steadyState(5.5, params);
        assertThat(s0.x3()).isEqualTo(0.0); // starts at zero
        HovorkaState s60 = s0;
        for (int i = 0; i < 60; i++) {
            s60 = solver.step(s60, params, 0.0, 70, 0.0, 0.0, 0.0);
        }
        assertThat(s60.x3()).isLessThan(0.001); // stays near zero (no insulin driver)
    }

    @Test
    void x3Dynamics_dynamicEgp_lowerThanStaticWhenInsulinated() {
        // With population egp0 > egpNet, after a bolus x3 rises and EGP is suppressed
        // below egp0. This tests that EGP(t) = egp0*(1-x3) < egp0 when x3 > 0.
        double weight  = params.weightKg();
        double popEgp0 = HovorkaParameters.EGP0_PER_KG * weight; // 1.127 > f01 = 0.679
        HovorkaParameters pPop = new HovorkaParameters(
                params.vG(), params.f01(), params.egpNet(), popEgp0,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                params.tMaxG(), params.aG(), params.isf(), weight);

        // With egp0 > egpNet and x3=0: EGP = egp0 > egpNet → glucose rises without insulin
        HovorkaState s0 = HovorkaState.steadyState(7.2, pPop);
        double bigEffect = 5.0 * pPop.isf() * pPop.effectiveInsulinVolume() / 60.0;

        HovorkaState sWithInsulin = s0;
        HovorkaState sNoInsulin   = s0;
        for (int i = 0; i < 120; i++) {
            sWithInsulin = solver.step(sWithInsulin, pPop, 0.0, 70, 0.0, bigEffect, 0.0);
            sNoInsulin   = solver.step(sNoInsulin,   pPop, 0.0, 70, 0.0, 0.0,        0.0);
        }
        // With insulin: x3 > 0 → EGP suppressed → glucose lower than no-insulin
        assertThat(sWithInsulin.x3()).isGreaterThan(sNoInsulin.x3());
        assertThat(sWithInsulin.glucoseMmolL(pPop)).isLessThan(sNoInsulin.glucoseMmolL(pPop));
    }

    // -- Sentinel: HovorkaState 10-field record (Task 4) -----------------------

    @Test
    void hovorkaState_hasX3Field() {
        HovorkaState s = new HovorkaState(1, 2, 3, 4, 5, 6, 0.0, 0.0, 0.0, 70);
        assertThat(s.x3()).isEqualTo(0.0);
        assertThat(s.protFatGut()).isEqualTo(0.0);
        assertThat(s.activeGI()).isEqualTo(70);
    }

    // -- Helper: OpenAPS IOB (same formula as InsulinCalculatorService) --------

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
