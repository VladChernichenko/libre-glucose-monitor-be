package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.ai.ContextAggregatorService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the duplications surfaced by the 2026-08-08 dual-computation audit.
 *
 * <p>Every assertion here binds two declarations of one number that agree today with nothing
 * enforcing it. These are not bug reports - they all pass at {@code 8b5d8c3}. They exist so that
 * a future edit to one side fails the build instead of silently desynchronising the two, which
 * is the failure mode the audit was commissioned to find and which a markdown finding cannot
 * prevent.
 *
 * <p>Findings pinned: {@code 05-context-aggregator.md} (Finding C), {@code 06-basal-egp.md}
 * (PEAK_X3_BASAL), {@code 07-gut-rates.md} (the 1.68 literal, weak form only - see that fragment
 * and {@code 10-pinning-tests.md} for why the strong form is unreachable here).
 *
 * <p>If one of these fails, do not widen the tolerance. Re-derive the constant, or unify the
 * declarations.
 */
class DuplicatedConstantPinningTest {

    // -- 06-basal-egp.md ------------------------------------------------------

    @Test
    @DisplayName("PEAK_X3_BASAL stays consistent with 1 - F01_PER_KG/EGP0_PER_KG")
    void peakX3Basal_matchesItsDerivation() {
        double derived = 1.0 - HovorkaParameters.F01_PER_KG / HovorkaParameters.EGP0_PER_KG;

        // The declared constant is that derivation rounded to 2 dp (0.39751... -> 0.40). The
        // tolerance pins the RELATIONSHIP, not the literal: move F01_PER_KG or EGP0_PER_KG and
        // this fails, which is the point. The nearest pre-existing test
        // (HovorkaOdeSolverTest.basalResolver_suppressionCurve_followsExpectedProfile) asserts
        // suppressionCurve() == PEAK_X3_BASAL, which is self-referential and would still pass if
        // the constant were changed to 0.50.
        assertThat(BasalInsulinResolver.PEAK_X3_BASAL)
                .as("PEAK_X3_BASAL (%s) is documented at BasalInsulinResolver:34-35 as "
                        + "1 - F01/EGP0 = %s; one of the three constants moved", 
                        BasalInsulinResolver.PEAK_X3_BASAL, derived)
                .isCloseTo(derived, within(0.005));
    }

    // -- 05-context-aggregator.md, Finding C ----------------------------------

    @Test
    @DisplayName("Constants declared privately in both GlucoseCalculationsService and ContextAggregatorService agree")
    void duplicatedConstants_agreeAcrossServices() throws Exception {
        assertConstantsAgree("DEFAULT_CARB_RATIO");
        assertConstantsAgree("DEFAULT_ISF");
        assertConstantsAgree("PRE_BOLUS_MAX_TIMING_EFFECT");
    }

    @Test
    @DisplayName("calculatePreBolusTimingContribution is behaviourally identical in both services")
    void preBolusTimingContribution_identicalAcrossServices() throws Exception {
        Object calcSvc = bareInstance(GlucoseCalculationsService.class);
        Object ctxSvc  = bareInstance(ContextAggregatorService.class);
        Method calcM = privateMethod(GlucoseCalculationsService.class);
        Method ctxM  = privateMethod(ContextAggregatorService.class);

        // Crosses every branch: null, the <10 arm, both boundaries, the flat 10-25 arm, and the
        // >25 arm through to saturation against PRE_BOLUS_MAX_TIMING_EFFECT.
        Double[] inputs = {null, 0.0, 5.0, 9.9, 10.0, 20.0, 25.0, 25.1, 45.0, 100.0};

        for (Double in : inputs) {
            double fromCalc = (double) calcM.invoke(calcSvc, in);
            double fromCtx  = (double) ctxM.invoke(ctxSvc, in);
            assertThat(fromCtx)
                    .as("calculatePreBolusTimingContribution(%s): "
                            + "GlucoseCalculationsService:455 gave %s, "
                            + "ContextAggregatorService:189 gave %s - the two copies have diverged",
                            in, fromCalc, fromCtx)
                    .isEqualTo(fromCalc);
        }
    }

    // -- 07-gut-rates.md (weak form) ------------------------------------------

    @Test
    @DisplayName("HALF_LIFE_TO_TMAX_G is the single source for the 2-compartment factor")
    void halfLifeToTMaxG_pinnedAtItsPublishedValue() {
        // HovorkaOdeSolver:349 hardcodes `p.tMaxG() * 1.68` instead of reading this constant,
        // while eight other sites read the constant. The strong pin - asserting derivatives()
        // and the warm-up replay produce the same four effective rates - is NOT reachable: the
        // replay's derivation is inline inside the private HovorkaGlucosePredictionService
        // .buildWarmState (:483), and extracting it is a production change this audit forbids.
        // Until that literal is unified, this makes a change to the named constant fail loudly
        // rather than silently desynchronise the ODE from every caller that respects it.
        assertThat(HovorkaParameterService.HALF_LIFE_TO_TMAX_G)
                .as("HovorkaOdeSolver:349 hardcodes 1.68; changing this constant alone would "
                        + "desynchronise the ODE from the warm-up replay, MacroNutrientGastricModel, "
                        + "PredictionReplayEngine and GlucosePredictService")
                .isEqualTo(1.68);
    }

    // -- helpers ---------------------------------------------------------------

    private static void assertConstantsAgree(String name) throws Exception {
        double fromCalc = constant(GlucoseCalculationsService.class, name);
        double fromCtx  = constant(ContextAggregatorService.class, name);
        assertThat(fromCtx)
                .as("%s is declared privately in both GlucoseCalculationsService and "
                        + "ContextAggregatorService; they have diverged (%s vs %s)",
                        name, fromCalc, fromCtx)
                .isEqualTo(fromCalc);
    }

    private static double constant(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(null);
    }

    private static Method privateMethod(Class<?> owner) throws Exception {
        Method m = owner.getDeclaredMethod("calculatePreBolusTimingContribution", Double.class);
        m.setAccessible(true);
        return m;
    }

    /**
     * Builds an instance with every constructor argument null. Sound only because
     * {@code calculatePreBolusTimingContribution} reads no instance state - it is a pure function
     * of its argument and one static constant. If that ever stops being true this throws rather
     * than silently testing something else.
     */
    private static Object bareInstance(Class<?> owner) throws Exception {
        Constructor<?> ctor = owner.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(new Object[ctor.getParameterCount()]);
    }
}
