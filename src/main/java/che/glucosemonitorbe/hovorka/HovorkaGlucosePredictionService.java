package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Hovorka-based glucose prediction path builder.
 *
 * <p>Replaces {@code GlucoseCalculationsService.buildPredictionPath()} when
 * {@code app.features.hovorka-model-enabled=true}.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Build {@link HovorkaParameters} from user ISF, CR, weight, carb half-life.</li>
 *   <li>Warm-start the state: Q1/Q2 from current CGM, D1/D2 from past meal history.</li>
 *   <li>Pre-compute IOB activity rate for each future minute using the OpenAPS curve.</li>
 *   <li>Add future (prospective) meal and insulin events to timelines.</li>
 *   <li>Integrate the 4-variable ODE system with RK4 at 1-min resolution.</li>
 *   <li>Emit {@link PredictionPointDTO} every 5 min (0-4 h) or 10 min (4-8 h).</li>
 * </ol>
 *
 * <h3>Insulin effect modelling</h3>
 * <p>Rather than tracking plasma insulin concentration through S1->S2->I_plasma compartments,
 * we compute the bolus IOB activity rate directly from the proven OpenAPS exponential curve:</p>
 * <pre>
 *   iobActivityRate(t) = max(0, IOB(t) - IOB(t+1))    [units/min]
 *   insulinEffect(t)   = ISF × VG × iobActivityRate(t) [mmol/min]
 * </pre>
 * <p>This preserves exact IOB pharmacokinetics while avoiding unit-conversion errors in
 * the full Hovorka subcutaneous absorption chain (S1->S2->I_plasma).</p>
 *
 * <h3>EGP modelling</h3>
 * <p>Net EGP is calibrated by {@link BasalInsulinResolver} from the user's long-acting notes.
 * At steady state with therapeutic basal, egpNet = F01 (hepatic output = brain/RBC uptake).
 * If basal wanes, egpNet rises above F01 -> fasting hyperglycaemia in the prediction.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HovorkaGlucosePredictionService {

    // -- Emission schedule (matches existing OpenAPS path) ---------------------
    private static final int DENSE_STEP_MIN   = 5;
    private static final int SPARSE_STEP_MIN  = 10;
    private static final int DENSE_LIMIT_MIN  = 240;

    private static final double G_MIN           = 1.0;
    private static final double G_MAX           = 25.0;

    /** GI assumed for a meal with no glycemic-index estimate - one definition for both timelines. */
    private static final int    DEFAULT_GI      = HovorkaOdeSolver.DEFAULT_GI;

    /**
     * Minutes over which the learned residual bias phases in from zero at the anchor.
     *
     * <p>{@link che.glucosemonitorbe.hovorka.learning.ResidualBiasModel} is fitted from
     * {@link che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine.Config#sampleHorizons}
     * - by default 30/60/90/120 min. It therefore describes how far the model has drifted by
     * <em>at least</em> half an hour out, and says nothing about shorter horizons. Applying it
     * at full strength to the first emitted point (+5 min) asserts the model is already wrong
     * by the full bias when it is anchored on a measured reading and its error is 0 by
     * construction - which drew a vertical step between "now" and the start of the forecast.
     *
     * <p>Ramping to full by the shortest fitted horizon leaves every sampled horizon
     * (>= 30 min) bit-identical, so calibration and the {@code applied} gate are unaffected.
     */
    private static final double RESIDUAL_RAMP_MINUTES = 30.0;

    /** Fraction of the learned residual applied {@code minute} past the anchor. */
    static double residualRamp(int minute) {
        if (minute <= 0) return 0.0;
        return Math.min(1.0, minute / RESIDUAL_RAMP_MINUTES);
    }

    private final HovorkaParameterService       paramService;
    private final HovorkaOdeSolver              odeSolver;
    private final BasalInsulinResolver          basalResolver;
    private final UserInsulinPreferencesService insulinPrefsService;
    private final DallaManGutModel              gutModel;
    private final UserSettingsService            userSettingsService;
    /** Auto-applies the learned digital-twin residual correction to emitted points (predictions only).
     *  Use {@link PredictionResidualProvider#NONE} to run the raw model (e.g. during calibration). */
    private final PredictionResidualProvider    residualProvider;

    /**
     * Build the full prediction path using the Hovorka ODE model.
     * Parameters are loaded from the user's COB settings and experiments.
     *
     * @param currentGlucose         current CGM reading [mmol/L]
     * @param currentTime            client-side "now"
     * @param pastCarbsEntries       carb events from the last 8 h (for gut warm-up)
     * @param pastInsulinDoses       bolus doses from the last 8 h (for IOB curve)
     * @param longActingNotes        long-acting notes from the last 36 h (for EGP)
     * @param userId                 user UUID
     * @param pathMinutes            total prediction horizon [min] - 240 or 480
     * @return                       list of prediction points, emitted every 5/10 min
     */
    public List<PredictionPointDTO> buildPredictionPath(
            double currentGlucose,
            LocalDateTime currentTime,
            List<CarbsEntry> pastCarbsEntries,
            List<InsulinDose> pastInsulinDoses,
            List<Note> longActingNotes,
            UUID userId,
            int pathMinutes) {

        HovorkaParameters p      = paramService.buildForUser(userId);
        RapidInsulinIobParameters rapidIob = insulinPrefsService.getRapidIobParameters(userId);
        UserSettingsDTO settings  = userSettingsService.getUserSettings(userId);
        return buildWithParams(p, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                ActivityProvider.NONE);
    }

    /**
     * DB-fetching prediction path that also accounts for logged activity via {@code activityProvider}
     * (use {@link ActivityProvider#NONE} for the un-modulated model).
     */
    public List<PredictionPointDTO> buildPredictionPath(
            double currentGlucose,
            LocalDateTime currentTime,
            List<CarbsEntry> pastCarbsEntries,
            List<InsulinDose> pastInsulinDoses,
            List<Note> longActingNotes,
            UUID userId,
            int pathMinutes,
            ActivityProvider activityProvider) {

        HovorkaParameters p      = paramService.buildForUser(userId);
        RapidInsulinIobParameters rapidIob = insulinPrefsService.getRapidIobParameters(userId);
        UserSettingsDTO settings  = userSettingsService.getUserSettings(userId);
        return buildWithParams(p, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                activityProvider);
    }

    /**
     * Build the prediction path with pre-built (possibly macro-modulated) parameters.
     *
     * <p>Used by the {@code /api/predict} endpoint where
     * {@link MacroNutrientGastricModel} has already computed a meal-specific tMaxG.</p>
     *
     * @param customParams           pre-built HovorkaParameters (tMaxG may be overridden)
     * @param currentGlucose         current CGM reading [mmol/L]
     * @param currentTime            client-side "now"
     * @param pastCarbsEntries       carb events from the last 8 h (for gut warm-up)
     * @param pastInsulinDoses       bolus doses from the last 8 h (for IOB curve)
     * @param longActingNotes        long-acting notes from the last 36 h (for EGP)
     * @param userId                 user UUID (needed for IOB preferences only)
     * @param pathMinutes            total prediction horizon [min]
     * @return                       list of prediction points, emitted every 5/10 min
     */
    public List<PredictionPointDTO> buildPredictionPath(
            HovorkaParameters customParams,
            double currentGlucose,
            LocalDateTime currentTime,
            List<CarbsEntry> pastCarbsEntries,
            List<InsulinDose> pastInsulinDoses,
            List<Note> longActingNotes,
            UUID userId,
            int pathMinutes) {

        RapidInsulinIobParameters rapidIob = insulinPrefsService.getRapidIobParameters(userId);
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        return buildWithParams(customParams, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                ActivityProvider.NONE);
    }

    /** Pre-built-parameters prediction path that also accounts for logged activity. */
    public List<PredictionPointDTO> buildPredictionPath(
            HovorkaParameters customParams,
            double currentGlucose,
            LocalDateTime currentTime,
            List<CarbsEntry> pastCarbsEntries,
            List<InsulinDose> pastInsulinDoses,
            List<Note> longActingNotes,
            UUID userId,
            int pathMinutes,
            ActivityProvider activityProvider) {

        RapidInsulinIobParameters rapidIob = insulinPrefsService.getRapidIobParameters(userId);
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        return buildWithParams(customParams, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                activityProvider);
    }

    /**
     * Build the prediction path with pre-built parameters <b>and</b> pre-fetched IOB/settings.
     *
     * <p>Hot-path overload for calibration replay: the caller supplies {@code rapidIob} and
     * {@code settings} once so the inner loop never re-reads them per anchor (the calibrator invokes
     * this tens of thousands of times). Behaviour is otherwise identical to the DB-fetching overload.</p>
     */
    public List<PredictionPointDTO> buildPredictionPath(
            HovorkaParameters customParams,
            RapidInsulinIobParameters rapidIob,
            UserSettingsDTO settings,
            double currentGlucose,
            LocalDateTime currentTime,
            List<CarbsEntry> pastCarbsEntries,
            List<InsulinDose> pastInsulinDoses,
            List<Note> longActingNotes,
            UUID userId,
            int pathMinutes) {

        return buildWithParams(customParams, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                ActivityProvider.NONE);
    }

    /**
     * Activity-aware overload: same as the hot-path overload but modulated by an {@link ActivityProvider}
     * supplying {@code a(t) ∈ [0,1]}. With {@link ActivityProvider#NONE} the result is identical to the
     * un-modulated model. Used by the offline activity-validation harness.
     */
    public List<PredictionPointDTO> buildPredictionPath(
            HovorkaParameters customParams,
            RapidInsulinIobParameters rapidIob,
            UserSettingsDTO settings,
            double currentGlucose,
            LocalDateTime currentTime,
            List<CarbsEntry> pastCarbsEntries,
            List<InsulinDose> pastInsulinDoses,
            List<Note> longActingNotes,
            UUID userId,
            int pathMinutes,
            ActivityProvider activityProvider) {

        return buildWithParams(customParams, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                activityProvider);
    }

    /**
     * Core ODE integration - shared by both public overloads.
     */
    private List<PredictionPointDTO> buildWithParams(
            HovorkaParameters p,
            RapidInsulinIobParameters rapidIob,
            UserSettingsDTO settings,
            double currentGlucose,
            LocalDateTime currentTime,
            List<CarbsEntry> pastCarbsEntries,
            List<InsulinDose> pastInsulinDoses,
            List<Note> longActingNotes,
            UUID userId,
            int pathMinutes,
            ActivityProvider activityProvider) {

        // -- State warm-up -----------------------------------------------------
        WarmUp warmUp = buildWarmState(currentGlucose, pastCarbsEntries, currentTime, p);
        HovorkaState state = warmUp.state();

        // -- EGP net from long-acting insulin ----------------------------------
        double x3Basal  = basalResolver.resolveEgpSuppression(longActingNotes, currentTime);
        double egp0Abs  = HovorkaParameters.EGP0_PER_KG * p.weightKg();
        double egpNow   = basalResolver.netEgp(p.f01(), egp0Abs, x3Basal);
        // No basal logged -> assume fasting steady state (T1D on continuous unlogged background basal).
        // Without this guard, egpNow = EGP0 >> f01 and glucose rises ~9 mmol/L in 4 h with no COB/IOB.
        if (longActingNotes == null || longActingNotes.isEmpty()) {
            egpNow = p.f01();
        }
        // Re-parameterise: set egpNet = egpNow (basal-adjusted) and egp0 = egpNow as well.
        // Gap 1 (EGP suppression) is fully active during active boluses: x3 rises with plasma
        // insulin, suppressing EGP beyond its basal level. However, the basal/fasting case is
        // not improved here — egp0 is set equal to egpNow so EGP(t=0) = egpNow regardless of
        // x3's value, keeping it stable without a modelled basal PK compartment. A complete
        // basal EGP bias correction requires a steady-state PK compartment (future work).
        HovorkaParameters pAdj = new HovorkaParameters(
                p.vG(), p.f01(), egpNow, egpNow, p.k12(), p.k21(),
                p.tMaxG(), p.aG(), p.isf(), p.weightKg());

        // With egp0 = egpNow and x3=0: EGP(0) = egpNow*(1-0) = egpNow (correct starting EGP).
        // x3Init = 0: no prior x3 suppression needed; boluses drive x3 up during the prediction.
        // x3 stays near 0 without insulin (dx3 = 0 at x3=0, plasmInsulin=0).
        state = state.withX3(0.0);

        // -- Pre-compute per-dose IOB timelines, each tagged with the ISF that --
        //    was in effect when that dose was administered --------------------
        List<DoseActivity> doseActivities = buildDoseActivities(pastInsulinDoses, currentTime,
                pathMinutes, rapidIob, settings, pAdj.isf());

        // -- Future carb timeline (prospective notes already in pastCarbsEntries
        //    with future timestamps - carbs at t <= now were in the warm-up) ---
        Map<Integer, Double> futureCarbs = buildFutureCarbTimeline(
                pastCarbsEntries, currentTime, pAdj);

        // -- Future GI timeline: parallel map of minute -> GI for future meals --
        Map<Integer, Integer> futureGiMap = buildFutureGiTimeline(pastCarbsEntries, currentTime);

        // -- Future tMaxG timeline: parallel map of minute -> gut time constant. A rescue carb
        //    absorbs at its own rate here instead of the user's mixed-meal rate. -------------
        Map<Integer, Double> futureTMaxGMap =
                buildFutureTMaxGTimeline(pastCarbsEntries, currentTime, pAdj);

        // -- Future protein+fat timeline: drives protFatGut compartment (GLP-1 ileal brake) --
        Map<Integer, Double> futureProtFat = buildFutureProtFatTimeline(pastCarbsEntries, currentTime);

        // -- Integration loop (1-min steps) -----------------------------------

        List<PredictionPointDTO> points = new ArrayList<>();
        int nextEmit = DENSE_STEP_MIN;

        // tMaxG of the meal currently being absorbed, carried across the warm-up boundary and
        // refreshed on each new meal - the same lifecycle activeGI has inside the solver. pStep is
        // pAdj until a meal actually changes tMaxG, so a prediction with no rescue carb in it runs
        // on the identical parameter object it always did.
        double activeTMaxG = warmUp.activeTMaxG();
        HovorkaParameters pStep = activeTMaxG == pAdj.tMaxG() ? pAdj : withTMaxG(pAdj, activeTMaxG);

        // CGM measurement model: what the sensor would read, not what plasma does. Seeded at the
        // anchor, where interstitial and plasma coincide. Pass-through unless the lag is configured.
        InterstitialLagModel sensor = InterstitialLagModel.startingAt(state.glucoseMmolL(pAdj));

        // -- Activity modulation (a(t) -> insulin-sensitivity amplification + insulin-independent
        //    uptake). Inert with the NONE provider - that path is bit-identical to the base model. --
        boolean hasActivity = activityProvider != ActivityProvider.NONE;
        ActivityModulation activity = new ActivityModulation();
        if (hasActivity) {
            // Warm-start the post-exercise sensitivity tail from activity just before "now".
            for (int m = ActivityModulation.WARMUP_MINUTES; m >= 1; m--) {
                activity.stepSensitivity(activityProvider.intensityAt(currentTime.minusMinutes(m)));
            }
        }

        for (int min = 1; min <= pathMinutes; min++) {
            // insulinEffect: mmol of glucose removed from Q1 per minute, summed across all
            // active doses. effectiveInsulinVolume = 2×VG corrects for the 2-compartment
            // distribution factor (see HovorkaParameters.effectiveInsulinVolume() for
            // derivation). Each dose's ISF was resolved once, from the time the dose was
            // administered - a manual isfBreakfast/isfLunch/isfDinner override applies to a
            // dose's entire activity curve if the dose was given in that window, even once
            // most of its activity plays out after the window ends (e.g. a dinner-time
            // correction bolus peaking after 22:00 still uses isfDinner).
            double insulinEffect = 0.0;
            for (DoseActivity dose : doseActivities) {
                // IOB activity rate: how many units/min this dose is "working" during the
                // step that advances state from t=now+(min-1) to t=now+min, i.e. the IOB
                // decay during [min-1, min] - NOT [min, min+1].
                double iobActivityRate = iobActivityRate(dose.iobTimeline(), min - 1);
                insulinEffect += dose.isf() * pAdj.effectiveInsulinVolume() * iobActivityRate;
            }

            // Future carbs delivered to gut D1 at this minute [mmol]
            double carbMmol = futureCarbs.getOrDefault(min, 0.0);
            int    mealGI   = futureGiMap.getOrDefault(min, state.activeGI());
            double protFatKcalNow = futureProtFat.getOrDefault(min, 0.0);

            // A new meal takes over the gut time constant, mirroring how step() lets a new meal
            // take over activeGI. Carbs arriving at this minute are the trigger for both.
            double mealTMaxG = futureTMaxGMap.getOrDefault(min, activeTMaxG);
            if (carbMmol > 0 && mealTMaxG != activeTMaxG) {
                activeTMaxG = mealTMaxG;
                pStep = withTMaxG(pAdj, activeTMaxG);
            }

            if (hasActivity) {
                double aInst = activityProvider.intensityAt(currentTime.plusMinutes(min));
                double aSens = activity.stepSensitivity(aInst);
                double insulinEffectMod = insulinEffect * activity.insulinSensitivityFactor(aSens);
                state = odeSolver.step(state, pStep, carbMmol, mealGI, protFatKcalNow, insulinEffectMod, activity.uptakeRate(aInst));
            } else {
                state = odeSolver.step(state, pStep, carbMmol, mealGI, protFatKcalNow, insulinEffect, 0.0);
            }

            // Advance the sensor model every minute, not only at emission points.
            double gSensed = sensor.step(state.glucoseMmolL(pAdj));

            if (min == nextEmit) {
                LocalDateTime pointTime = currentTime.plusMinutes(min);
                double gPred = gSensed;
                // Digital-twin residual correction (predictions only): add the learned per-hour bias
                // that the physiology can't express from logged inputs, then re-clamp to the
                // physiological range. NONE provider (calibration replay) leaves gPred untouched.
                double correction =
                        residualProvider.residualMmol(userId, pointTime) * residualRamp(min);
                double gAdj = Math.max(G_MIN, Math.min(G_MAX, gPred + correction));
                double giScaleDisplay = HovorkaOdeSolver.giScale(state.activeGI());
                // pStep, not pAdj: the reported carb effect must be drawn with the same kAbs the
                // ODE just integrated with, or a rescue would show a mixed-meal absorption rate.
                double kAbsDisplay = DallaManGutModel.effectiveKAbs(pStep.tMaxG()) * giScaleDisplay;
                double carbEffect  = gutModel.ra(state.qgut(), kAbsDisplay) * DENSE_STEP_MIN;
                double insulinEff  = -insulinEffect * DENSE_STEP_MIN;

                points.add(PredictionPointDTO.builder()
                        .timestamp(pointTime)
                        .predictedGlucose(Math.round(gAdj * 10.0) / 10.0)
                        .carbAbsorptionEffect(Math.round(carbEffect * 100.0) / 100.0)
                        .insulinActivityEffect(Math.round(insulinEff * 100.0) / 100.0)
                        .absorptionMode("DALLA_MAN_3COMP")
                        .build());

                nextEmit += (min < DENSE_LIMIT_MIN ? DENSE_STEP_MIN : SPARSE_STEP_MIN);
            }
        }

        return points;
    }

    // -- State warm-up ---------------------------------------------------------

    /**
     * Initialises the extended state reflecting the current physiological state.
     *
     * <ul>
     *   <li>Q1, Q2 - from current CGM glucose (steady-state approximation).</li>
     *   <li>Qsto1, Qsto2, Qgut - by replaying each past meal through the
     *       Dalla Man gut ODE up to "now". This gives accurate nonlinear
     *       absorption state without shortcuts.</li>
     *   <li>ProtFatGut, Inc, activeGI - carried by the same replay, so an already-logged meal
     *       enters the forward integration with the same GI scaling and GLP-1 state a meal
     *       timestamped a minute into the future would have.</li>
     * </ul>
     *
     * <p>The replay mirrors {@link HovorkaOdeSolver#derivatives} term for term. Anything modelled
     * there and not here is silently dropped for every meal the user has actually logged: the
     * warm-up used to hard-code {@code activeGI = 70} and leave {@code protFatGut = 0}, so GI,
     * protein and fat only ever reached the model through the future-event timelines and a meal
     * one minute old predicted nothing like the same meal one minute ahead.</p>
     */
    private WarmUp buildWarmState(
            double currentGlucose,
            List<CarbsEntry> pastCarbs,
            LocalDateTime now,
            HovorkaParameters p) {

        HovorkaState ss = HovorkaState.steadyState(currentGlucose, p);

        // Drain rates mirror the forward RK4 integration (HovorkaOdeSolver.derivatives) term for
        // term, so a meal's Qgut carries over consistently across the warm-up/forward boundary.
        // Both the caloric correction and kAbs derive from tMaxG there, and both are therefore
        // computed per tick here - from the tMaxG and GI of the meal being absorbed at that tick.

        // Collect past meals (delivered before "now") as age-in-minutes -> carb mmol, GI and
        // protein+fat kcal. We replay ALL of them through ONE shared gut chain in chronological
        // order - not isolated per-meal chains - so the k_empt D reference is refreshed to the
        // stomach load at each ingestion exactly like HovorkaOdeSolver.step. This makes stacked
        // history meals (e.g. snack then dinner) carry the same emptying dynamics into the
        // forward integration as the forward path itself, and keeps a single fresh meal
        // consistent with a continuous run that started at the meal.
        Map<Integer, Double> mealsByAge     = new HashMap<>();
        Map<Integer, Double> giWeightedByAge = new HashMap<>();
        Map<Integer, Double> giWeightByAge   = new HashMap<>();
        Map<Integer, Double> tMaxGWeightedByAge = new HashMap<>();
        Map<Integer, Double> protFatByAge    = new HashMap<>();
        // Ages at which a rescue carb was ingested - the only ages whose tMaxG is worth blending.
        Set<Integer> rescueAges = new HashSet<>();
        int oldestAge = 0;
        for (CarbsEntry entry : pastCarbs) {
            if (entry.getTimestamp() == null) continue;
            long minsAgo = minsAgoFromNow(entry.getTimestamp(), now);
            if (minsAgo <= 0) continue;
            int ageMin = (int) Math.min(minsAgo, 480);

            // Protein/fat drive the GLP-1 ileal brake even when the meal carries no carbs at all.
            double kcal = protFatKcal(entry);
            if (kcal > 0) {
                protFatByAge.merge(ageMin, kcal, Double::sum);
                oldestAge = Math.max(oldestAge, ageMin);
            }

            double carbMmol = toCarbMmol(entry, p);
            if (carbMmol <= 0) continue;
            mealsByAge.merge(ageMin, carbMmol, Double::sum);
            // Carb-weighted GI, matching buildFutureGiTimeline: meals landing on the same
            // minute blend rather than the later one silently winning.
            double carbs = entry.getCarbs();
            giWeightedByAge.merge(ageMin, carbs * giOf(entry), Double::sum);
            giWeightByAge.merge(ageMin, carbs, Double::sum);
            // Carb-weighted tMaxG over the same weights, so a rescue swallowed alongside a normal
            // snack blends rather than one of the two silently winning the minute.
            tMaxGWeightedByAge.merge(ageMin, carbs * tMaxGOf(entry, p), Double::sum);
            if (RescueCarbProfile.isRescue(entry.getAbsorptionMode())) {
                rescueAges.add(ageMin);
            }
            oldestAge = Math.max(oldestAge, ageMin);
        }

        if (mealsByAge.isEmpty() && protFatByAge.isEmpty()) {
            return new WarmUp(ss, p.tMaxG());
        }

        // Blend only where a rescue actually contributed. Where every entry carries the user's own
        // tMaxG the blend is p.tMaxG() by definition, but the divide can land a unit in the last
        // place away from it - and that would perturb every ordinary meal's curve for no reason.
        Map<Integer, Double> tMaxGByAge = new HashMap<>();
        rescueAges.forEach(age ->
                tMaxGByAge.put(age, tMaxGWeightedByAge.get(age) / giWeightByAge.get(age)));

        double qsto1 = 0.0, qsto2 = 0.0, qgut = 0.0, dRef = 0.0;
        double protFatGut = 0.0, inc = 0.0;
        int activeGi = DEFAULT_GI;
        double activeTMaxG = p.tMaxG();
        // Tick down from the oldest meal's age to 1 minute ago. At each tick, ingest any
        // meal whose age equals the current tick, then advance the gut ODE by one minute.
        for (int age = oldestAge; age >= 1; age--) {
            Double carbMmol = mealsByAge.get(age);
            if (carbMmol != null) {
                qsto1 += carbMmol;
                dRef   = qsto1 + qsto2;   // refresh D = stomach load, like step()
                activeGi = (int) Math.round(giWeightedByAge.get(age) / giWeightByAge.get(age));
                activeTMaxG = tMaxGByAge.getOrDefault(age, p.tMaxG());
            }
            Double kcal = protFatByAge.get(age);
            if (kcal != null) {
                protFatGut += kcal;
            }

            // Caloric correction and kAbs both from the absorbing meal's own tMaxG, and the GI
            // scale on top of both - identical to derivatives(), which derives them from p.tMaxG().
            double cCal    = DallaManGutModel.caloricScale(
                    activeTMaxG * HovorkaParameterService.HALF_LIFE_TO_TMAX_G);
            double giScale = HovorkaOdeSolver.giScale(activeGi);
            double kGriEff = DallaManGutModel.K_GRI * cCal * giScale;
            double kMaxEff = DallaManGutModel.K_MAX * cCal * giScale;
            double kMinEff = DallaManGutModel.K_MIN * cCal * giScale;
            double kAbsEff = DallaManGutModel.effectiveKAbs(activeTMaxG) * giScale;

            double qsto  = qsto1 + qsto2;
            double kempt = dRef > 0 ? gutModel.kEmpt(qsto, dRef, kMaxEff, kMinEff) : 0.0;
            // Ileal brake: GLP-1 from protein/fat already in the gut slows emptying.
            double kemptEff = kempt * HovorkaOdeSolver.ilealBrake(inc);

            double dQsto1 = -kGriEff * qsto1;
            double dQsto2 = kGriEff * qsto1 - kemptEff * qsto2;
            double dQgut  = kemptEff * qsto2 - kAbsEff * qgut;
            double dProtFatGut = -HovorkaOdeSolver.K_PF_DRAIN * protFatGut;
            double dInc        = HovorkaOdeSolver.dIncDt(protFatGut, inc);

            qsto1 = Math.max(0.0, qsto1 + dQsto1);
            qsto2 = Math.max(0.0, qsto2 + dQsto2);
            qgut  = Math.max(0.0, qgut  + dQgut);
            protFatGut = Math.max(0.0, protFatGut + dProtFatGut);
            inc        = Math.max(0.0, inc + dInc);
        }

        HovorkaState warm = new HovorkaState(
                ss.q1(), ss.q2(), qsto1, qsto2, qgut, inc, 0.0, protFatGut, dRef, activeGi);

        log.debug("Dalla Man warm state: G={}mmol/L Q1={} Qsto1={} Qsto2={} Qgut={} Inc={} ProtFatGut={} GI={} tMaxG={}min",
                currentGlucose, warm.q1(), warm.qsto1(), warm.qsto2(), warm.qgut(),
                warm.inc(), warm.protFatGut(), warm.activeGI(), activeTMaxG);
        return new WarmUp(warm, activeTMaxG);
    }

    /**
     * The state at "now" plus the gut time constant of the meal still absorbing there.
     *
     * <p>{@code activeTMaxG} rides alongside {@link HovorkaState} rather than inside it because the
     * solver reads tMaxG from {@link HovorkaParameters}, not from the state vector. It has to cross
     * the warm-up/forward boundary all the same: a rescue logged a minute ago must keep draining at
     * its own rate once the forward integration takes over, exactly as {@code activeGI} does.</p>
     */
    private record WarmUp(HovorkaState state, double activeTMaxG) {}

    // -- Per-dose IOB timelines -----------------------------------------------

    /**
     * A single insulin dose's IOB [units] timeline (index 0 = current time, index m =
     * currentTime + m minutes) paired with the ISF [mmol/L per unit] resolved from the
     * moment the dose was administered.
     */
    private record DoseActivity(double[] iobTimeline, double isf) {}

    /**
     * Pre-computes, for each past or prospective dose, its IOB timeline from the OpenAPS
     * curve and the ISF in effect when it was administered: the user's manual
     * isfBreakfast/isfLunch/isfDinner/isfNight override for the dose's own meal window, or
     * {@code fallbackIsf} if none applies.
     */
    private List<DoseActivity> buildDoseActivities(
            List<InsulinDose> doses,
            LocalDateTime now,
            int pathMinutes,
            RapidInsulinIobParameters rapidIob,
            UserSettingsDTO settings,
            double fallbackIsf) {

        int size = pathMinutes + 2;
        List<DoseActivity> activities = new ArrayList<>();
        for (InsulinDose dose : doses) {
            if (dose.getTimestamp() == null || dose.getUnits() == null) continue;
            double minsAgoDose = minsAgoFromNow(dose.getTimestamp(), now);
            double[] iob = new double[size];
            for (int m = 0; m < size; m++) {
                // Elapsed time since this dose at prediction step m. For past doses
                // (minsAgoDose > 0), more time has passed by step m, so IOB must keep
                // decaying - NOT fold back toward the full dose. For prospective doses
                // (minsAgoDose < 0), this is negative until m reaches the delivery
                // minute; iobOpenApsExponential returns 0 for negative input.
                double minsAgoAtStep = minsAgoDose + m;
                iob[m] = InsulinCalculatorService.iobOpenApsExponential(
                        dose.getUnits(), minsAgoAtStep,
                        rapidIob.diaHours(), rapidIob.peakMinutes());
            }
            double isf = resolveIsf(settings, fallbackIsf, dose.getTimestamp());
            activities.add(new DoseActivity(iob, isf));
        }
        return activities;
    }

    /**
     * Resolves the ISF [mmol/L per unit] in effect at {@code time}: the user's manual
     * per-meal-window override (isfBreakfast/isfLunch/isfDinner/isfNight) if one applies to
     * {@code time}'s meal window, otherwise {@code fallbackIsf} (the Hovorka-calibrated
     * ISF from {@link HovorkaParameterService#buildForUser}).
     */
    private double resolveIsf(UserSettingsDTO settings, double fallbackIsf, LocalDateTime time) {
        if (settings == null) {
            return fallbackIsf;
        }
        Double effectiveIsf = settings.getEffectiveIsf(time);
        return effectiveIsf != null ? effectiveIsf : fallbackIsf;
    }

    /**
     * Minutes elapsed from {@code eventTimestamp} to {@code now}: positive when the
     * event is in the past, negative when it is still ahead (a prospective entry).
     * Shared by the warm-up replay, IOB timeline, and future-carb timeline so all
     * three use the same "elapsed time since event" sign convention.
     */
    private long minsAgoFromNow(LocalDateTime eventTimestamp, LocalDateTime now) {
        return Duration.between(eventTimestamp, now).toMinutes();
    }

    /**
     * IOB activity rate at minute m [units/min] - the rate at which IOB is being consumed.
     * Computed as a forward difference: max(0, IOB[m] - IOB[m+1]).
     */
    private double iobActivityRate(double[] iobTimeline, int m) {
        if (m + 1 >= iobTimeline.length) return 0.0;
        return Math.max(0.0, iobTimeline[m] - iobTimeline[m + 1]);
    }

    // -- Future carb timeline --------------------------------------------------

    /**
     * Builds a map of minute -> carb mmol for future events only.
     * Past events (minsAgo > 0) are already captured in the warm-up.
     */
    private Map<Integer, Double> buildFutureCarbTimeline(
            List<CarbsEntry> carbsEntries,
            LocalDateTime now,
            HovorkaParameters p) {

        Map<Integer, Double> timeline = new HashMap<>();
        for (CarbsEntry entry : carbsEntries) {
            if (entry.getTimestamp() == null) continue;
            long minsAgo = minsAgoFromNow(entry.getTimestamp(), now);
            if (minsAgo > 0) continue; // past - captured in warm-up

            // Future or current event: minute = |minsAgo|.
            // Clamp to 1: minsAgo=0 (meal logged at exactly "now") must still enter the ODE loop,
            // which starts at min=1 - storing at key=0 would cause the meal to be silently dropped.
            int futureMin = Math.max(1, (int) Math.abs(minsAgo));
            double carbMmol = toCarbMmol(entry, p);
            if (carbMmol > 0) {
                timeline.merge(futureMin, carbMmol, Double::sum);
            }
        }
        return timeline;
    }

    /**
     * Builds a map of minute -> GI for future carb events only.
     * Mirrors the minute-offset logic of {@link #buildFutureCarbTimeline}: past entries
     * (minsAgo > 0) are captured in the warm-up and excluded here.
     * Default GI of 70 is used when {@code estimatedGi} is null.
     *
     * <p>When multiple meals land at the same minute, computes the carb-weighted average GI
     * instead of silently dropping the first meal (which would happen with {@code put}).
     * Accumulates weighted numerator (gi × carbMmol) and carb denominator separately,
     * then divides to get the final weighted-average GI per minute.</p>
     */
    private Map<Integer, Integer> buildFutureGiTimeline(
            List<CarbsEntry> carbsEntries, LocalDateTime now) {
        Map<Integer, Double> giWeightedSum = new HashMap<>();
        Map<Integer, Double> carbWeightMap  = new HashMap<>();

        for (CarbsEntry entry : carbsEntries) {
            if (entry.getTimestamp() == null) continue;
            long minsAgo = minsAgoFromNow(entry.getTimestamp(), now);
            if (minsAgo > 0) continue; // past - captured in warm-up

            int futureMin = Math.max(1, (int) Math.abs(minsAgo));
            double carbs = entry.getCarbs() != null ? entry.getCarbs() : 0.0;
            if (carbs <= 0.0) continue;
            giWeightedSum.merge(futureMin, carbs * giOf(entry), Double::sum);
            carbWeightMap.merge(futureMin, carbs, Double::sum);
        }

        Map<Integer, Integer> timeline = new HashMap<>();
        giWeightedSum.forEach((min, weightedGi) -> {
            double totalCarbs = carbWeightMap.getOrDefault(min, 1.0);
            timeline.put(min, (int) Math.round(weightedGi / totalCarbs));
        });
        return timeline;
    }

    /**
     * Maps future minute-offset → carb-weighted gut time constant [min]. Mirrors
     * {@link #buildFutureGiTimeline} exactly - same {@code minsAgo > 0} skip, same carb weights -
     * so a rescue carb contributes the fast rescue tMaxG and a hypo treatment absorbs at its own
     * rate rather than the user's mixed-meal rate.
     *
     * <p>A minute with no rescue carb maps to {@code p.tMaxG()} verbatim rather than to a
     * carb-weighted average of identical values. The average <em>is</em> {@code p.tMaxG()}
     * mathematically, but the divide can land a unit in the last place away from it, and that would
     * perturb every ordinary meal's curve for no reason.</p>
     */
    private Map<Integer, Double> buildFutureTMaxGTimeline(
            List<CarbsEntry> carbsEntries, LocalDateTime now, HovorkaParameters p) {
        Map<Integer, Double> tMaxGWeightedSum = new HashMap<>();
        Map<Integer, Double> carbWeightMap    = new HashMap<>();
        Set<Integer> rescueMinutes            = new HashSet<>();

        for (CarbsEntry entry : carbsEntries) {
            if (entry.getTimestamp() == null) continue;
            long minsAgo = minsAgoFromNow(entry.getTimestamp(), now);
            if (minsAgo > 0) continue; // past - captured in warm-up

            int futureMin = Math.max(1, (int) Math.abs(minsAgo));
            double carbs = entry.getCarbs() != null ? entry.getCarbs() : 0.0;
            if (carbs <= 0.0) continue;
            tMaxGWeightedSum.merge(futureMin, carbs * tMaxGOf(entry, p), Double::sum);
            carbWeightMap.merge(futureMin, carbs, Double::sum);
            if (RescueCarbProfile.isRescue(entry.getAbsorptionMode())) {
                rescueMinutes.add(futureMin);
            }
        }

        Map<Integer, Double> timeline = new HashMap<>();
        carbWeightMap.forEach((min, totalCarbs) -> timeline.put(min,
                rescueMinutes.contains(min)
                        ? tMaxGWeightedSum.get(min) / totalCarbs
                        : p.tMaxG()));
        return timeline;
    }

    /**
     * Maps future minute-offset → protein+fat caloric load [kcal] entering the gut.
     * Used to drive the protFatGut compartment (GLP-1 ileal brake).
     *
     * <p>Mirrors the minute-offset logic of {@link #buildFutureCarbTimeline}: past entries
     * (minsAgo &gt; 0) are already captured in the warm-up and excluded here.</p>
     */
    private Map<Integer, Double> buildFutureProtFatTimeline(
            List<CarbsEntry> carbsEntries, LocalDateTime now) {
        Map<Integer, Double> timeline = new HashMap<>();
        for (CarbsEntry entry : carbsEntries) {
            if (entry.getTimestamp() == null) continue;
            long minsAgo = minsAgoFromNow(entry.getTimestamp(), now);
            if (minsAgo > 0) continue; // past - captured in warm-up
            int futureMin = Math.max(1, (int) Math.abs(minsAgo));
            double kcal = protFatKcal(entry);
            if (kcal > 0) timeline.merge(futureMin, kcal, Double::sum);
        }
        return timeline;
    }

    /**
     * Converts a {@link CarbsEntry} to gut mmol input using the Hovorka formula:
     * <pre>
     *   carbMmol = grams × A_G / 0.18
     * </pre>
     * where 0.18 is the molar mass of glucose (g/mmol, MW = 180 g/mol / 1000).
     *
     * <p>{@code A_G} is a per-user meal-magnitude calibration trim (≈ 1.0), <b>not</b> a
     * bioavailability factor. Physiological carb bioavailability (~0.90) is applied exactly
     * once, downstream, by {@link DallaManGutModel#ra} ({@code F = 0.90}): the full meal dose
     * D = grams × A_G / 0.18 enters the stomach, and only {@code F} of it ultimately appears
     * in blood. Applying a second sub-1.0 factor here (as the old CR-coupled A_G did) would
     * double-discount carbs and systematically under-predict the post-meal rise.</p>
     */
    private double toCarbMmol(CarbsEntry entry, HovorkaParameters p) {
        if (entry.getCarbs() == null || entry.getCarbs() <= 0) return 0.0;
        return entry.getCarbs() * p.aG() / 0.18;
    }

    /**
     * A meal's glycemic index, or {@link #DEFAULT_GI} when it carries no estimate.
     * Shared by the warm-up replay and the future timeline so a meal's GI does not depend on
     * which side of "now" it was logged.
     */
    private int giOf(CarbsEntry entry) {
        return entry.getEstimatedGi() != null ? entry.getEstimatedGi().intValue() : DEFAULT_GI;
    }

    /**
     * A meal's gut time constant [min]: the fast rescue value for a hypo treatment, otherwise the
     * user's own tMaxG. Shared by the warm-up replay and the future timeline for the same reason as
     * {@link #giOf} - a rescue logged one minute ago and one logged one minute ahead must absorb
     * identically.
     */
    private double tMaxGOf(CarbsEntry entry, HovorkaParameters p) {
        return RescueCarbProfile.isRescue(entry.getAbsorptionMode())
                ? HovorkaParameterService.rescueTMaxG()
                : p.tMaxG();
    }

    /**
     * A copy of {@code p} with {@code tMaxG} replaced - the only lever the ODE offers for "these
     * carbs absorb at a different speed".
     *
     * <p>{@link HovorkaOdeSolver#derivatives} reads {@code p.tMaxG()} twice: once for the caloric
     * correction on k_gri/k_max/k_min (gastric emptying) and once for {@code effectiveKAbs}
     * (intestinal drain). Both must move together for a rescue carb, so the per-minute value is
     * threaded in through the parameter record rather than as a separate argument - the same
     * mechanism {@link MacroNutrientGastricModel} already uses on the {@code /api/predict} path.</p>
     */
    private static HovorkaParameters withTMaxG(HovorkaParameters p, double tMaxG) {
        return new HovorkaParameters(
                p.vG(), p.f01(), p.egpNet(), p.egp0(), p.k12(), p.k21(),
                tMaxG, p.aG(), p.isf(), p.weightKg());
    }

    /**
     * A meal's protein+fat caloric load [kcal] - the GLP-1 driver behind the ileal brake.
     * Shared by the warm-up replay and the future timeline, same reason as {@link #giOf}.
     */
    private double protFatKcal(CarbsEntry entry) {
        double proteinKcal = entry.getProtein() != null ? entry.getProtein() * 4.0 : 0.0;
        double fatKcal     = entry.getFat()     != null ? entry.getFat()     * 9.0 : 0.0;
        return proteinKcal + fatKcal;
    }

    /** Round to 1 decimal place [mmol/L] - matches the predictedGlucose precision. */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
