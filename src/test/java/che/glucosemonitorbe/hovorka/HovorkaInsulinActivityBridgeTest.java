package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.Mockito.mock;

/**
 * The x3 bridge approximates plasma insulin from insulin activity. It used to divide the
 * insulin effect by a single ISF, but the effect is a sum over doses each carrying the ISF of
 * the window it was given in - so one ISF was dividing a multi-ISF sum. The caller now passes
 * the summed activity rate directly and the ISF cancels out of the solver.
 *
 * <p>The last two tests are golden-curve characterization fixtures: their expected values were
 * generated from the implementation itself (captured before this change and re-verified byte for
 * byte after it), not derived from an independent model. They exist to pin the emitted curve
 * against unintended movement, not to prove it is physiologically right.</p>
 */
class HovorkaInsulinActivityBridgeTest {

    private final HovorkaOdeSolver solver = new HovorkaOdeSolver(new DallaManGutModel());

    private static final double WEIGHT = 84.0;

    private static HovorkaParameters params(double isf) {
        return new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * WEIGHT,
                HovorkaParameters.F01_PER_KG * WEIGHT,
                HovorkaParameters.F01_PER_KG * WEIGHT,
                HovorkaParameters.EGP0_PER_KG * WEIGHT,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G,
                1.0, isf, WEIGHT);
    }

    /**
     * Single-ISF equivalence: feeding the explicit rate must reproduce exactly what the old
     * division produced, so no existing forecast moves. The aggregate-effect overload still
     * performs that division, so it stands in for the pre-change code path.
     */
    @Test
    @DisplayName("explicit rate reproduces the old ISF division when all doses share one ISF")
    void explicitRateReproducesTheOldDivisionWhenAllDosesShareOneIsf() {
        HovorkaParameters p = params(1.5);
        double rate = 0.004;                                     // units/min
        double effect = 1.5 * p.effectiveInsulinVolume() * rate;  // one dose at isf 1.5

        HovorkaState viaRate = solver.step(
                HovorkaState.steadyState(7.0, p), p, 0.0, 70, 0.0, effect, rate, 0.0);

        // The pre-change path: the aggregate-effect overload, which recovers rate as
        // effect / (isf x V) - and for one dose at the parameter ISF that IS rate.
        HovorkaState viaDivision = solver.step(
                HovorkaState.steadyState(7.0, p), p, 0.0, 70, 0.0, effect, 0.0);

        assertThat(viaRate.x3()).isEqualTo(viaDivision.x3());
        assertThat(viaRate.q1()).isEqualTo(viaDivision.q1());
    }

    /**
     * Two doses at different window ISFs. The correct plasma-insulin driver is the sum of the
     * activity rates, independent of either ISF - the old form would have produced an
     * ISF-weighted blend instead.
     */
    @Test
    @DisplayName("plasma insulin tracks the total activity rate, not an ISF-weighted blend")
    void plasmaInsulinTracksTotalActivityRateNotTheIsfBlend() {
        HovorkaParameters p = params(1.0);
        double v = p.effectiveInsulinVolume();
        double rateA = 0.003, rateB = 0.002;
        double effect = 1.5 * v * rateA + 1.0 * v * rateB;   // dinner dose + night dose
        double totalRate = rateA + rateB;

        HovorkaState after = solver.step(
                HovorkaState.steadyState(7.0, p), p, 0.0, 70, 0.0, effect, totalRate, 0.0);

        // dx3 = -KA3*x3 + KB3*plasmInsulin with plasmInsulin = totalRate * V_I_SCALE, integrated
        // over one RK4 minute from x3 = 0. x3 decouples from the rest of the system (nothing else
        // feeds it), so the scalar RK4 below is the exact expected value, not an approximation.
        double expected = rk4X3(0.0, HovorkaOdeSolver.KB3 * totalRate * HovorkaOdeSolver.V_I_SCALE);
        assertThat(after.x3()).isCloseTo(expected, offset(expected * 1e-12));

        // Pinned literal, deliberately NOT expressed through the constants above: it is the one
        // assertion in this file that moves if KA3, KB3 or V_I_SCALE is retuned. The emitted curve
        // does not - x3 is worth ~1e-3 mmol/L at therapeutic doses, well under the 0.1 mmol/L the
        // forecast is rounded to, so the golden fixtures below cannot see a change in this bridge.
        assertThat(after.x3()).isCloseTo(8.866339875e-07, offset(1e-18));

        // And the same activity rate must drive x3 identically whatever the parameter ISF is:
        // the quantity the bridge inverts no longer appears in it.
        HovorkaState atOtherIsf = solver.step(
                HovorkaState.steadyState(7.0, params(3.7)), params(3.7),
                0.0, 70, 0.0, effect, totalRate, 0.0);
        assertThat(atOtherIsf.x3()).isEqualTo(after.x3());
    }

    /**
     * Bit-identity over a full 5-hour horizon at raw double precision, not at the 0.1 mmol/L the
     * emitted curve is rounded to: a real bolus decay curve, one ISF, driven through the explicit
     * rate on one side and through the aggregate-effect division on the other.
     */
    @Test
    @DisplayName("a 300-minute bolus decay is bit-identical through either overload at one ISF")
    void longRunSingleIsfIntegrationIsBitIdentical() {
        HovorkaParameters p = params(2.0);
        double v = p.effectiveInsulinVolume();
        double[] iob = new double[302];
        for (int m = 0; m < iob.length; m++) {
            iob[m] = InsulinCalculatorService.iobOpenApsExponential(4.0, m, 4.5, 55.0);
        }

        HovorkaState viaRate = HovorkaState.steadyState(9.4, p);
        HovorkaState viaDivision = HovorkaState.steadyState(9.4, p);
        for (int min = 1; min <= 300; min++) {
            double rate = Math.max(0.0, iob[min - 1] - iob[min]);
            double effect = 2.0 * v * rate;                       // exactly as the caller sums it
            viaRate = solver.step(viaRate, p, 0.0, 70, 0.0, effect, rate, 0.0);
            viaDivision = solver.step(viaDivision, p, 0.0, 70, 0.0, effect, 0.0);
            assertThat(viaRate.q1()).isEqualTo(viaDivision.q1());
            assertThat(viaRate.glucoseMmolL(p)).isEqualTo(viaDivision.glucoseMmolL(p));
        }
    }

    // -- Golden curve: one bolus, one meal, one activity note, one ISF ---------

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 21, 18, 30);
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final RapidInsulinIobParameters IOB = new RapidInsulinIobParameters(4.5, 55.0);

    /** Emission schedule of the fixture: 5-min points to +240, then 10-min to +300. */
    private static final int[] GOLDEN_MINUTES = goldenMinutes();

    /** Carb-absorption effect [mmol/5 min]; identical on both branches (activity does not touch the gut). */
    private static final double[] GOLDEN_CARB_EFFECT = {
            0.7, 2.28, 4.11, 5.53, 6.17, 6.25, 6.07, 5.79, 5.5, 5.22, 4.96, 4.74, 4.54,
            4.37, 4.22, 4.08, 3.96, 3.85, 3.75, 3.66, 3.57, 3.49, 3.4, 3.32, 3.24, 3.17,
            3.09, 3.02, 2.94, 2.87, 2.8, 2.73, 2.66, 2.59, 2.52, 2.46, 2.39, 2.33, 2.27,
            2.21, 2.15, 2.09, 2.03, 1.98, 1.93, 1.87, 1.82, 1.77, 1.68, 1.59, 1.5, 1.42,
            1.34, 1.27
    };

    /** Insulin effect [mmol/5 min]; unmodulated by activity, so identical on both branches. */
    private static final double[] GOLDEN_INSULIN_EFFECT = {
            -1.66, -3.22, -4.51, -5.55, -6.39, -7.04, -7.54, -7.9, -8.13, -8.27, -8.31,
            -8.29, -8.2, -8.05, -7.87, -7.64, -7.39, -7.12, -6.83, -6.53, -6.22, -5.91,
            -5.6, -5.28, -4.97, -4.67, -4.37, -4.08, -3.8, -3.53, -3.26, -3.01, -2.77,
            -2.54, -2.33, -2.12, -1.92, -1.74, -1.56, -1.4, -1.25, -1.1, -0.97, -0.84,
            -0.72, -0.62, -0.52, -0.42, -0.26, -0.12, -0.01, 0.0, 0.0, 0.0
    };

    private static final double[] GOLDEN_GLUCOSE_NO_ACTIVITY = {
            9.4, 9.4, 9.3, 9.3, 9.3, 9.3, 9.2, 9.2, 9.1, 9.0, 8.9, 8.8, 8.7, 8.5, 8.4,
            8.3, 8.1, 8.0, 7.9, 7.8, 7.6, 7.5, 7.5, 7.4, 7.3, 7.2, 7.2, 7.1, 7.1, 7.1,
            7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 7.1, 7.1, 7.1, 7.2, 7.2, 7.3,
            7.3, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9, 8.0
    };

    private static final double[] GOLDEN_GLUCOSE_WITH_WALK = {
            9.4, 9.4, 9.3, 9.3, 9.2, 9.0, 8.8, 8.6, 8.3, 8.0, 7.7, 7.4, 7.1, 6.8, 6.5,
            6.3, 6.0, 5.7, 5.5, 5.3, 5.1, 4.9, 4.7, 4.6, 4.4, 4.3, 4.2, 4.1, 4.0, 4.0,
            3.9, 3.9, 3.9, 3.9, 3.9, 3.9, 3.9, 3.9, 3.9, 4.0, 4.0, 4.0, 4.1, 4.1, 4.2,
            4.2, 4.3, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9
    };

    @Test
    @DisplayName("golden curve: 45 g + 4 U at one ISF, no activity, is unchanged by the rate bridge")
    void goldenCurveWithoutActivity() {
        assertGolden(predict(ActivityProvider.NONE), GOLDEN_GLUCOSE_NO_ACTIVITY);
    }

    /**
     * The same forecast with a mid-meal walking note. The 21 Aug incident had exactly this shape,
     * so the activity branch - which scales both the effect and the rate by the sensitivity factor
     * - is pinned as tightly as the plain one.
     */
    @Test
    @DisplayName("golden curve: the same forecast with a mid-meal walking note is unchanged too")
    void goldenCurveWithActivityNote() {
        assertGolden(predict(NotesActivityProvider.fromNotes(List.of(walkNote()))),
                GOLDEN_GLUCOSE_WITH_WALK);
    }

    // -- helpers ---------------------------------------------------------------

    /** Scalar RK4 of dx3/dt = -KA3*x3 + c over one minute, with a constant driver c. */
    private static double rk4X3(double x0, double c) {
        double a = HovorkaOdeSolver.KA3;
        double k1 = -a * x0 + c;
        double k2 = -a * (x0 + 0.5 * k1) + c;
        double k3 = -a * (x0 + 0.5 * k2) + c;
        double k4 = -a * (x0 + k3) + c;
        return x0 + (k1 + 2 * k2 + 2 * k3 + k4) / 6.0;
    }

    private void assertGolden(List<PredictionPointDTO> curve, double[] expectedGlucose) {
        assertThat(curve).hasSize(GOLDEN_MINUTES.length);
        for (int i = 0; i < GOLDEN_MINUTES.length; i++) {
            PredictionPointDTO pt = curve.get(i);
            String at = "+" + GOLDEN_MINUTES[i] + " min";
            assertThat(pt.getTimestamp()).as("timestamp at %s", at)
                    .isEqualTo(T0.plusMinutes(GOLDEN_MINUTES[i]));
            assertThat(pt.getPredictedGlucose()).as("glucose at %s", at)
                    .isEqualTo(expectedGlucose[i]);
            assertThat(pt.getCarbAbsorptionEffect()).as("carb effect at %s", at)
                    .isEqualTo(GOLDEN_CARB_EFFECT[i]);
            assertThat(pt.getInsulinActivityEffect()).as("insulin effect at %s", at)
                    .isEqualTo(GOLDEN_INSULIN_EFFECT[i]);
        }
    }

    private List<PredictionPointDTO> predict(ActivityProvider provider) {
        List<CarbsEntry> carbs = List.of(CarbsEntry.builder().timestamp(T0).carbs(45.0).build());
        List<InsulinDose> insulin = List.of(InsulinDose.builder()
                .timestamp(T0).units(4.0).type(InsulinDose.InsulinType.BOLUS).build());
        // settings = null -> no per-window ISF override, so every dose is priced at params.isf().
        return rawPredictor().buildPredictionPath(
                params(2.0), IOB, null, 9.4, T0, carbs, insulin, List.of(), USER, 300, provider);
    }

    private static Note walkNote() {
        Note n = new Note();
        n.setType(Note.TYPE_ACTIVITY);
        n.setActivityType("WALKING");
        n.setIntensity("MODERATE");
        n.setDurationMin(40);
        n.setTimestamp(T0.plusMinutes(20));
        return n;
    }

    private static int[] goldenMinutes() {
        int[] minutes = new int[54];
        int min = 5;
        for (int i = 0; i < minutes.length; i++) {
            minutes[i] = min;
            min += (min < 240 ? 5 : 10);
        }
        return minutes;
    }

    private static HovorkaGlucosePredictionService rawPredictor() {
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaOdeSolver odeSolver = new HovorkaOdeSolver(gut);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        return new HovorkaGlucosePredictionService(
                mock(HovorkaParameterService.class), odeSolver, basal,
                mock(UserInsulinPreferencesService.class), gut,
                mock(UserSettingsService.class), PredictionResidualProvider.NONE);
    }
}
