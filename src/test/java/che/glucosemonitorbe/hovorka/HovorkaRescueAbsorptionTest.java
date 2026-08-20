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
                .isBetween(bothNormal, bothRescue);
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
