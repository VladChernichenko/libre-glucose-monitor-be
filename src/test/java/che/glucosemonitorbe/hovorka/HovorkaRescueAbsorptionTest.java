package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * A hypo treatment must absorb at its own rate in the Hovorka prediction curve.
 *
 * <h3>Bug</h3>
 * {@code tMaxG} was a single per-user value read once from {@code user_settings.carb_half_life}
 * (26.8 min at the 45-min default), so a rescue carb was absorbed through the user's mixed-meal
 * gut curve by the ODE that draws the chart - roughly 3x too slowly - while {@code CarbsOnBoardService}
 * showed the very same carbs cleared at 45 min. The headline COB number and the chart beside it told
 * a patient two different stories about a hypo recovery.
 *
 * <h3>Fix</h3>
 * Both gut paths - the warm-up replay and the forward timeline - now carb-weight {@code tMaxG} per
 * minute exactly as they already carb-weight GI, and a {@code "RESCUE"}-marked entry contributes
 * {@link HovorkaParameterService#rescueTMaxG()} instead of the user's own value.
 *
 * <p>Every comparison below holds GI fixed at {@link RescueCarbProfile#GI} on both sides, so what is
 * being measured is the new tMaxG lever alone and not the pre-existing GI scaling.</p>
 */
@ExtendWith(MockitoExtension.class)
class HovorkaRescueAbsorptionTest {

    @Mock HovorkaParameterService       paramService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock UserSettingsService           userSettingsService;

    private HovorkaGlucosePredictionService service;
    private HovorkaParameters params;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 1, 10, 0);

    /** A hypo, which is the only situation a rescue carb is logged in. */
    private static final double HYPO_GLUCOSE = 3.6;
    /** Typical hypo treatment: 3-4 dextrose tablets. */
    private static final double RESCUE_GRAMS = 15.0;

    /** Emission spacing inside the 4 h dense window - used to label golden-curve failures. */
    private static final int DENSE_EMIT_MIN = 5;

    @BeforeEach
    void setUp() {
        DallaManGutModel gutModel  = new DallaManGutModel();
        HovorkaOdeSolver solver    = new HovorkaOdeSolver(gutModel);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        service = new HovorkaGlucosePredictionService(
                paramService, solver, basal, insulinPrefsService, gutModel, userSettingsService,
                PredictionResidualProvider.NONE);

        double weight = 70.0;
        params = new HovorkaParameters(
                HovorkaParameters.VG_PER_KG  * weight,
                HovorkaParameters.F01_PER_KG * weight,
                HovorkaParameters.F01_PER_KG * weight,   // egpNet = f01 (steady state)
                HovorkaParameters.EGP0_PER_KG * weight,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G,   // the 45-min default
                1.0, 2.2, weight);

        // lenient: the rescueTMaxG test asserts on the constant alone and never runs a prediction.
        lenient().when(insulinPrefsService.getRapidIobParameters(any()))
                .thenReturn(new RapidInsulinIobParameters(4.5, 55.0));
    }

    // ---
    // 1. The constant itself
    // ---

    @Test
    @DisplayName("the rescue gut constant is derived from the rescue half-life and beats the user's")
    void rescueTMaxG_isDerivedFromTheRescueHalfLifeAndIsFasterThanTheUsersOwn() {
        double rescue = HovorkaParameterService.rescueTMaxG();

        assertThat(rescue)
                .as("rescueTMaxG must be RescueCarbProfile.HALF_LIFE_MIN converted through the same "
                    + "1.68 factor the per-user value uses - not a hand-copied number")
                .isEqualTo(RescueCarbProfile.HALF_LIFE_MIN / HovorkaParameterService.HALF_LIFE_TO_TMAX_G)
                .isCloseTo(8.93, within(0.01));

        // Asserted as a relationship, not a literal, so it still means something if the default
        // carb half-life moves: a rescue is pure glucose and must never absorb slower than a meal.
        assertThat(rescue)
                .as("a rescue must absorb strictly faster than the user's mixed-meal gut constant")
                .isLessThan(params.tMaxG());
    }

    // ---
    // 2. Forward timeline: a rescue logged right now
    // ---

    @Test
    @DisplayName("a rescue carb raises glucose sooner than the same grams logged as a normal meal")
    void rescueCarbRaisesGlucoseSoonerThanAnIdenticalNormalMeal() {
        double rescueAt30 = glucoseAt(curveFor(rescue(0)), 30);
        double normalAt30 = glucoseAt(curveFor(normal(0)), 30);

        assertThat(rescueAt30)
                .as("half an hour into a hypo treatment the rescue carbs must already be in the "
                    + "blood; on the user's mixed-meal curve they are still in the gut. "
                    + "rescue=%.2f normal=%.2f mmol/L", rescueAt30, normalAt30)
                .isGreaterThan(normalAt30);
    }

    @Test
    @DisplayName("a rescue's glucose appearance peaks earlier and higher than a normal meal's")
    void rescueGlucoseAppearancePeaksEarlierAndHigher() {
        List<PredictionPointDTO> rescueCurve = curveFor(rescue(0));
        List<PredictionPointDTO> normalCurve = curveFor(normal(0));

        assertThat(peakCarbEffectMinute(rescueCurve))
                .as("a rescue must dump its glucose into the blood early - that is the entire "
                    + "clinical point of taking one. rescue peaks at +%d min, normal at +%d min",
                    peakCarbEffectMinute(rescueCurve), peakCarbEffectMinute(normalCurve))
                .isLessThan(peakCarbEffectMinute(normalCurve));

        assertThat(peakCarbEffect(rescueCurve))
                .as("the same grams arriving in half the time must produce a taller Ra peak. "
                    + "rescue=%.2f normal=%.2f mmol per 5 min",
                    peakCarbEffect(rescueCurve), peakCarbEffect(normalCurve))
                .isGreaterThan(peakCarbEffect(normalCurve));
    }

    @Test
    @DisplayName("more of a rescue is absorbed inside its 45-min window than of a normal meal")
    void moreOfARescueIsAbsorbedInsideTheRescueWindow() {
        double rescueFraction = absorbedFractionBy(curveFor(rescue(0)), RescueCarbProfile.MAX_DURATION_MIN);
        double normalFraction = absorbedFractionBy(curveFor(normal(0)), RescueCarbProfile.MAX_DURATION_MIN);

        // NOTE: the rescue fraction is around 0.46, not ~1.0. kAbs is not the rate limiter here -
        // the Dalla Man gastric-emptying chain is, and its caloricScale is clamped at 1.5x. Lifting
        // that clamp would change every non-rescue meal's curve, so it is deliberately out of scope.
        // The prediction curve therefore still runs a tail past the point COB reports zero; see the
        // task report. What must hold is that a rescue is decisively front-loaded versus a meal.
        assertThat(rescueFraction)
                .as("fraction of total carb absorption delivered by +%d min: rescue=%.3f "
                    + "normal=%.3f", RescueCarbProfile.MAX_DURATION_MIN, rescueFraction, normalFraction)
                .isGreaterThan(normalFraction + 0.05);
    }

    // ---
    // 3. Warm-up replay: a rescue already swallowed
    // ---

    @Test
    @DisplayName("a rescue logged half an hour ago absorbs fast in the warm-up replay too")
    void rescueAlreadySwallowedAbsorbsFastInTheWarmUpReplay() {
        // Both entries are 30 min old, so both are replayed by buildWarmState and never touch the
        // future timeline. Without the warm-up half of the fix these two curves are identical.
        List<PredictionPointDTO> rescueCurve = curveFor(rescue(30));
        List<PredictionPointDTO> normalCurve = curveFor(normal(30));

        assertThat(carbEffectAt(rescueCurve, 5))
                .as("30 min post-dose the rescue's gut is draining hard; the mixed meal's is not. "
                    + "rescue=%.2f normal=%.2f mmol per 5 min",
                    carbEffectAt(rescueCurve, 5), carbEffectAt(normalCurve, 5))
                .isGreaterThan(carbEffectAt(normalCurve, 5));

        assertThat(carbEffectAt(rescueCurve, 120))
                .as("and by two hours the rescue must be the more spent of the two. "
                    + "rescue=%.2f normal=%.2f mmol per 5 min",
                    carbEffectAt(rescueCurve, 120), carbEffectAt(normalCurve, 120))
                .isLessThan(carbEffectAt(normalCurve, 120));
    }

    @Test
    @DisplayName("a rescue one minute old predicts like the same rescue one minute ahead")
    void rescueJustLoggedMatchesRescueJustAhead() {
        // minsAgo <= 0 is skipped by the warm-up and picked up by the forward timeline, so a rescue
        // logged at "now" crosses between the two paths on the next prediction. If only one path
        // knew about RESCUE the curve would change shape a minute after the user logged the hypo.
        CarbsEntry ahead = entry(NOW.plusMinutes(1), RescueCarbProfile.ABSORPTION_MODE);

        double justLogged = glucoseAt(curveFor(rescue(1)), 120);
        double justAhead  = glucoseAt(curveFor(ahead), 120);

        assertThat(justLogged)
                .as("shifting one rescue's timestamp by two minutes across 'now' must not change "
                    + "the forecast: warm-up replay and forward timeline must both absorb it fast")
                .isCloseTo(justAhead, within(0.2));
    }

    // ---
    // 4. Neutrality: nothing without the marker may change
    // ---

    @Test
    @DisplayName("an entry without the RESCUE marker is absorbed exactly as before")
    void entriesWithoutTheRescueMarkerAreUntouched() {
        // null, blank and any other absorption mode must all fall through to the user's tMaxG.
        List<PredictionPointDTO> noMode    = curveFor(entry(NOW.minusMinutes(20), null));
        List<PredictionPointDTO> otherMode = curveFor(entry(NOW.minusMinutes(20), "DALLA_MAN_3COMP"));

        assertThat(noMode).hasSameSizeAs(otherMode);
        for (int i = 0; i < noMode.size(); i++) {
            assertThat(otherMode.get(i).getPredictedGlucose())
                    .as("an unrecognised absorptionMode must not divert a meal onto the rescue curve")
                    .isEqualTo(noMode.get(i).getPredictedGlucose());
        }
    }

    @Test
    @DisplayName("a rescue and a normal snack in the same minute blend by carb weight")
    void rescueAndNormalCarbsInTheSameMinuteBlend() {
        CarbsEntry rescueHalf = entry(NOW, RescueCarbProfile.ABSORPTION_MODE);
        CarbsEntry normalHalf = entry(NOW, null);

        double blended    = glucoseAt(curve(List.of(rescueHalf, normalHalf)), 30);
        double bothRescue = glucoseAt(curve(List.of(rescueHalf, entry(NOW, RescueCarbProfile.ABSORPTION_MODE))), 30);
        double bothNormal = glucoseAt(curve(List.of(normalHalf, entry(NOW, null))), 30);

        assertThat(blended)
                .as("equal grams of rescue and mixed-meal carbs on one minute must land between the "
                    + "two pure cases, not let one silently win. blend=%.2f allRescue=%.2f "
                    + "allNormal=%.2f mmol/L", blended, bothRescue, bothNormal)
                // strictly: isBetween is inclusive and would pass if one side silently won outright.
                .isStrictlyBetween(bothNormal, bothRescue);
    }

    // ---
    // 5. Golden curve: the non-rescue path must not drift
    // ---

    /**
     * Emitted glucose [mmol/L] every 5 min for {@link #goldenScenario()}, and the matching carb
     * absorption effect. <b>Before/after baseline, not a snapshot of current output</b>: generated
     * by running this exact scenario against the source as it stood at commit {@code 4ceae15} -
     * immediately before {@code 1bd5af7} gave the Hovorka gut model a per-entry tMaxG - via
     * {@code git checkout 4ceae15 -- src/main/java/.../hovorka/} plus a throwaway print harness,
     * then restoring the working tree. Per-entry tMaxG must be exactly inert for any meal without
     * the RESCUE marker, so the current implementation must reproduce these numbers bit-for-bit;
     * any future edit that shifts an ordinary meal's curve has to change these literals
     * deliberately, not silently.
     */
    private static final double[] GOLDEN_GLUCOSE = {
             7.4,  7.4,  7.4,  7.3,  7.3,  7.2,  7.1,  7.0,  6.8,  6.7,  6.6,  6.4,
             6.3,  6.2,  6.1,  6.0,  6.1,  6.2,  6.5,  6.8,  7.1,  7.4,  7.7,  8.0,
             8.2,  8.5,  8.7,  8.9,  9.1,  9.2,  9.4,  9.6,  9.8,  9.9, 10.1, 10.2,
            10.4, 10.6, 10.7, 10.9, 11.0, 11.2, 11.3, 11.5, 11.6, 11.8, 12.0, 12.1};

    private static final double[] GOLDEN_CARB_EFFECT = {
             6.25,  6.22,  6.05,   5.8,  5.54,  5.26,   5.0,  4.75,  4.52,  4.31,  4.13,  3.96,
             3.81,  3.68,  4.91,  8.05, 10.51,  11.8, 11.96, 11.52, 10.85, 10.11,  9.39,  8.73,
             8.13,   7.6,  7.15,  6.75,   6.4,  6.11,  5.85,  5.63,  5.43,  5.26,  5.11,  4.98,
             4.86,  4.75,  4.65,  4.56,  4.48,  4.39,  4.32,  4.24,  4.17,   4.1,  4.04,  3.97};

    @Test
    @DisplayName("golden curve: an ordinary meal's forecast is unchanged to the last emitted digit")
    void ordinaryMealCurveMatchesTheGoldenValues() {
        // The task's central claim is that per-entry tMaxG is exactly neutral for non-rescue meals.
        // Asserting two non-rescue modes against each other cannot show that - both would drift
        // together. This pins the actual emitted numbers, exercising a past meal through the
        // warm-up replay with GI, protein, fat, a bolus and a basal note all in play.
        List<PredictionPointDTO> curve = goldenScenario();

        assertThat(curve).hasSize(GOLDEN_GLUCOSE.length);
        for (int i = 0; i < GOLDEN_GLUCOSE.length; i++) {
            int minute = (i + 1) * DENSE_EMIT_MIN;
            assertThat(curve.get(i).getPredictedGlucose())
                    .as("predicted glucose at +%d min", minute)
                    .isEqualTo(GOLDEN_GLUCOSE[i]);
            assertThat(curve.get(i).getCarbAbsorptionEffect())
                    .as("carb absorption effect at +%d min", minute)
                    .isEqualTo(GOLDEN_CARB_EFFECT[i]);
        }
    }

    /**
     * 60 g mixed meal 30 min ago with GI 55, 25 g protein, 20 g fat; 4 U bolus; basal 8 h ago;
     * plus a 40 g GI-65 snack 75 min from now. The past meal drives the warm-up replay; the future
     * snack drives the forward timeline's non-rescue fallback in {@code buildFutureTMaxGTimeline}
     * and the forward integration loop - neither meal carries the RESCUE marker, so this scenario's
     * whole job is to pin both gut paths' ordinary-meal behaviour.
     */
    private List<PredictionPointDTO> goldenScenario() {
        CarbsEntry pastMeal = CarbsEntry.builder()
                .timestamp(NOW.minusMinutes(30)).carbs(60.0).build();
        pastMeal.setEstimatedGi(55.0);
        pastMeal.setProtein(25.0);
        pastMeal.setFat(20.0);

        CarbsEntry futureSnack = CarbsEntry.builder()
                .timestamp(NOW.plusMinutes(75)).carbs(40.0).build();
        futureSnack.setEstimatedGi(65.0);

        InsulinDose bolus = new InsulinDose();
        bolus.setTimestamp(NOW.minusMinutes(25));
        bolus.setUnits(4.0);

        Note basal = new Note();
        basal.setTimestamp(NOW.minusHours(8));
        basal.setInsulin(20.0);
        basal.setType(Note.TYPE_LONG_ACTING);

        return service.buildPredictionPath(
                params, 7.4, NOW, List.of(pastMeal, futureSnack), List.of(bolus), List.of(basal),
                USER_ID, 240);
    }

    // ---
    // 6. The rescue rate must not outlive the rescue
    // ---

    @Test
    @DisplayName("a rescue does not speed up carbs that were already in the gut before it")
    void rescueDoesNotDrainAnEarlierMealAtRescueSpeedForever() {
        // A big dinner 90 min ago is still emptying when a hypo is treated. activeTMaxG is set by
        // the most recent carb minute and drains ALL residual Qgut, so without an expiry bound the
        // dinner would finish at rescue speed for the rest of the horizon - over-predicting recovery
        // at exactly the moment an optimistic curve is most dangerous.
        CarbsEntry dinner = CarbsEntry.builder()
                .timestamp(NOW.minusMinutes(90)).carbs(80.0).build();
        dinner.setEstimatedGi((double) RescueCarbProfile.GI);

        // Compare the late DECAY RATE, not the absolute effect. Absolute Ra confounds the rate
        // constant with how much is left in the gut, and the two scenarios legitimately differ on
        // the latter. Ra decays as exp(-kAbs*t), so the ratio across a fixed span isolates kAbs.
        double rescueDecay = lateDecayRatio(curve(List.of(dinner, rescue(0))));
        double normalDecay = lateDecayRatio(curve(List.of(dinner, normal(0))));

        assertThat(rescueDecay)
                .as("three hours out, long past the rescue's %d-min window, the dinner still in the "
                    + "gut must be draining at the user's own rate. Ra(180)/Ra(120): "
                    + "withRescue=%.4f withNormal=%.4f (unbounded rescue rate gives ~0.49)",
                    RescueCarbProfile.MAX_DURATION_MIN, rescueDecay, normalDecay)
                .isCloseTo(normalDecay, within(0.03));
    }

    @Test
    @DisplayName("a rescue whose window closed during the warm-up leaves no fast rate behind")
    void rescueWindowAlsoExpiresInsideTheWarmUpReplay() {
        // 60 min old: the whole 45-min window opens and closes inside buildWarmState's
        // age-descending loop, which needs its own expiry - the forward path's never runs here.
        double rescueDecay = lateDecayRatio(curveFor(rescue(60)));
        double normalDecay = lateDecayRatio(curveFor(normal(60)));

        assertThat(rescueDecay)
                .as("the warm-up replay must retire the rescue rate at %d min just as the forward "
                    + "timeline does. Ra(180)/Ra(120): rescue=%.4f normal=%.4f "
                    + "(unbounded rescue rate gives ~0.48)",
                    RescueCarbProfile.MAX_DURATION_MIN, rescueDecay, normalDecay)
                .isCloseTo(normalDecay, within(0.03));
    }

    // ---
    // Helpers
    // ---

    /** A carb entry of {@link #RESCUE_GRAMS} at {@code when}, GI fixed, with the given mode. */
    private CarbsEntry entry(LocalDateTime when, String absorptionMode) {
        CarbsEntry e = CarbsEntry.builder()
                .timestamp(when)
                .carbs(RESCUE_GRAMS)
                .build();
        e.setEstimatedGi((double) RescueCarbProfile.GI);
        e.setAbsorptionMode(absorptionMode);
        return e;
    }

    private CarbsEntry rescue(int minsAgo) {
        return entry(NOW.minusMinutes(minsAgo), RescueCarbProfile.ABSORPTION_MODE);
    }

    private CarbsEntry normal(int minsAgo) {
        return entry(NOW.minusMinutes(minsAgo), null);
    }

    private List<PredictionPointDTO> curveFor(CarbsEntry entry) {
        return curve(List.of(entry));
    }

    private List<PredictionPointDTO> curve(List<CarbsEntry> entries) {
        return service.buildPredictionPath(
                params, HYPO_GLUCOSE, NOW,
                entries, List.<InsulinDose>of(), List.<Note>of(),
                USER_ID, 240);
    }

    private double glucoseAt(List<PredictionPointDTO> curve, int minutesFromNow) {
        LocalDateTime target = NOW.plusMinutes(minutesFromNow);
        return curve.stream()
                .filter(pt -> pt.getTimestamp().equals(target))
                .mapToDouble(PredictionPointDTO::getPredictedGlucose)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no prediction point at +" + minutesFromNow + " min"));
    }

    /** Emitted glucose-appearance effect [mmol per emission step] at a given horizon. */
    private double carbEffectAt(List<PredictionPointDTO> curve, int minutesFromNow) {
        LocalDateTime target = NOW.plusMinutes(minutesFromNow);
        return curve.stream()
                .filter(pt -> pt.getTimestamp().equals(target))
                .mapToDouble(PredictionPointDTO::getCarbAbsorptionEffect)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no prediction point at +" + minutesFromNow + " min"));
    }

    /**
     * Ra(180)/Ra(120) - the tail decay of glucose appearance. Ra falls as exp(-kAbs*t), so this
     * ratio reflects the absorption rate constant alone, independent of how much is left in the gut.
     */
    private double lateDecayRatio(List<PredictionPointDTO> curve) {
        return carbEffectAt(curve, 180) / carbEffectAt(curve, 120);
    }

    private double peakCarbEffect(List<PredictionPointDTO> curve) {
        return curve.stream().mapToDouble(PredictionPointDTO::getCarbAbsorptionEffect).max().orElseThrow();
    }

    /** Minute offset at which glucose appearance peaks. */
    private int peakCarbEffectMinute(List<PredictionPointDTO> curve) {
        PredictionPointDTO peak = curve.stream()
                .max(Comparator.comparingDouble(PredictionPointDTO::getCarbAbsorptionEffect))
                .orElseThrow();
        return (int) Duration.between(NOW, peak.getTimestamp()).toMinutes();
    }

    /**
     * Share of the whole curve's glucose appearance that has been delivered by {@code byMinute}.
     * Emitted points are 5 min apart out to 4 h, so an unweighted sum is a fair proxy over that span.
     */
    private double absorbedFractionBy(List<PredictionPointDTO> curve, int byMinute) {
        double total = 0.0, early = 0.0;
        for (PredictionPointDTO pt : curve) {
            double effect = pt.getCarbAbsorptionEffect();
            total += effect;
            if (Duration.between(NOW, pt.getTimestamp()).toMinutes() <= byMinute) early += effect;
        }
        return early / total;
    }
}
