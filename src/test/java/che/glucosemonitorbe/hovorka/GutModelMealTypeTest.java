package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Regression tests for two gut-model bugs that broke "handle all types of meal":
 *
 * <ol>
 *   <li><b>Stacked-meal k_empt reference.</b> {@link HovorkaOdeSolver} used to track the
 *       Dalla Man D reference as a <i>cumulative</i> sum of every meal ever eaten. A fresh
 *       meal landing on a partly-digested earlier meal was then seen as a half-full large
 *       meal, throttling k_empt toward K_MIN and badly delaying absorption. The fix refreshes
 *       D to the stomach load at each ingestion. (Hit by every mixed meal, because
 *       GlucosePredictService injects a second FPU-equivalent carb entry.)</li>
 *   <li><b>Fast-carb absorption cap.</b> {@link DallaManGutModel#effectiveKAbs} clamped the
 *       absorption factor at 1, so juice / glucose tabs could not absorb faster than an
 *       average mixed meal. The fix allows speed-up for low tMaxG within physiological bounds.</li>
 * </ol>
 */
class GutModelMealTypeTest {

    private final DallaManGutModel gutModel = new DallaManGutModel();
    private final HovorkaOdeSolver solver   = new HovorkaOdeSolver(gutModel);

    private static HovorkaParameters params() {
        double weight = 70.0;
        return new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * weight,
                HovorkaParameters.F01_PER_KG * weight,
                HovorkaParameters.F01_PER_KG * weight,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 0.80, 2.2, weight);
    }

    // ---
    // Bug 1: stacked-meal D reference
    // ---

    @Test
    @DisplayName("adding carbs refreshes the k_empt D reference to the stomach load, not a cumulative sum")
    void step_addingSecondMeal_refreshesDReferenceToStomachLoad() {
        HovorkaParameters p = params();
        HovorkaState state = HovorkaState.steadyState(6.0, p);

        // First meal: 200 mmol onto an empty stomach. D = post-ingestion stomach load ≈ 200
        // (D is fixed at ingestion; the state has since drained ~1 min, so D >= current stomach).
        state = solver.step(state, p, 200.0, 0.0);
        assertThat(state.mealMmol())
                .as("D after first meal = ingested load (≈200), not a cumulative sum")
                .isCloseTo(200.0, within(1.0))
                .isGreaterThanOrEqualTo(state.qsto1() + state.qsto2());

        // Digest for 90 min with no new carbs - D stays fixed for this emptying episode.
        for (int m = 0; m < 90; m++) state = solver.step(state, p, 0.0, 0.0);
        double residualStomach = state.qsto1() + state.qsto2();

        // Second meal: 200 mmol lands on the residual. D must refresh to the NEW stomach load
        // (residual + 200), NOT the cumulative 400 the old code produced.
        state = solver.step(state, p, 200.0, 0.0);
        assertThat(state.mealMmol())
                .as("D after stacked meal = residual + new carbs (refreshed), not cumulative 400")
                .isCloseTo(residualStomach + 200.0, within(2.0))
                .isLessThan(390.0);
    }

    @Test
    @DisplayName("a meal stacked on a digesting meal still empties fast (k_empt near K_MAX), not throttled to K_MIN")
    void stackedMeal_emptiesFromFullStomachBranch_notThrottled() {
        HovorkaParameters p = params();
        HovorkaState state = HovorkaState.steadyState(6.0, p);

        state = solver.step(state, p, 200.0, 0.0);
        for (int m = 0; m < 90; m++) state = solver.step(state, p, 0.0, 0.0);

        // Add the second meal, then measure k_empt right after ingestion.
        state = solver.step(state, p, 200.0, 0.0);
        double kemptAfterStack = gutModel.kEmpt(state.qsto1() + state.qsto2(), state.mealMmol());

        // With the fix, D ≈ current stomach load -> "full stomach" branch -> near K_MAX.
        // With the old cumulative D ≈ 400 and stomach ≈ 210, this was the mid-fill dip -> near K_MIN.
        assertThat(kemptAfterStack)
                .as("stacked meal must empty near K_MAX=%.4f, not be throttled to K_MIN=%.4f",
                        DallaManGutModel.K_MAX, DallaManGutModel.K_MIN)
                .isGreaterThan((DallaManGutModel.K_MAX + DallaManGutModel.K_MIN) / 2.0);
    }

    @Test
    @DisplayName("two identical meals 90 min apart both produce a clear glucose rise (second not swallowed)")
    void twoStackedMeals_bothProduceGlucoseRise() {
        HovorkaParameters p = params();
        HovorkaState state = HovorkaState.steadyState(6.0, p);

        // Meal 1 at t=0.
        state = solver.step(state, p, 220.0, 0.0);
        double gAfterMeal1 = 0.0;
        for (int m = 1; m < 90; m++) {
            state = solver.step(state, p, 0.0, 0.0);
            gAfterMeal1 = Math.max(gAfterMeal1, state.glucoseMmolL(p));
        }
        double gAt90 = state.glucoseMmolL(p);

        // Meal 2 at t=90 (stacked on the tail of meal 1).
        state = solver.step(state, p, 220.0, 0.0);
        double gPeak2 = gAt90;
        for (int m = 91; m < 240; m++) {
            state = solver.step(state, p, 0.0, 0.0);
            gPeak2 = Math.max(gPeak2, state.glucoseMmolL(p));
        }

        assertThat(gPeak2)
                .as("second stacked meal must drive a further rise above the t=90 level (%.2f)", gAt90)
                .isGreaterThan(gAt90 + 1.0);
    }

    // ---
    // Bug 2: fast-carb absorption (effectiveKAbs)
    // ---

    @Test
    @DisplayName("fast carbs (low tMaxG) absorb FASTER than baseline - was previously capped at 1×")
    void effectiveKAbs_fastCarbs_exceedsBaseline() {
        double base = DallaManGutModel.BASE_T_MAX_G_MIN;   // ≈ 26.8 min
        double fast = base / 2.0;                          // juice / glucose tabs

        double kBase = DallaManGutModel.effectiveKAbs(base);
        double kFast = DallaManGutModel.effectiveKAbs(fast);

        assertThat(kBase).isCloseTo(DallaManGutModel.K_ABS, within(1e-9));
        assertThat(kFast)
                .as("low tMaxG must yield a higher effective K_ABS than baseline")
                .isGreaterThan(kBase);
    }

    @Test
    @DisplayName("slow carbs (high tMaxG) absorb slower; effectiveKAbs stays within physiological bounds")
    void effectiveKAbs_boundsRespected() {
        double slow = DallaManGutModel.BASE_T_MAX_G_MIN * 5.0;
        double kSlow = DallaManGutModel.effectiveKAbs(slow);

        assertThat(kSlow).isLessThan(DallaManGutModel.K_ABS);
        // Bounds: 0.2× .. 2.5× K_ABS for any input, including degenerate ones.
        for (double tMaxG : new double[]{0.0, 1.0, 5.0, 27.0, 120.0, 1000.0}) {
            double k = DallaManGutModel.effectiveKAbs(tMaxG);
            assertThat(k)
                    .as("effectiveKAbs(%.1f) within [0.2,2.5]×K_ABS", tMaxG)
                    .isBetween(0.2 * DallaManGutModel.K_ABS, 2.5 * DallaManGutModel.K_ABS);
        }
    }
}
