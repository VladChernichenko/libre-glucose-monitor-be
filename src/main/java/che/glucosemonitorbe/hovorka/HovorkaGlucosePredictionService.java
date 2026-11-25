package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.sick.SickProfile;
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
     * A course eaten up to this many minutes before a carb meal counts as its first course: its
     * fiber is added to the meal's own when slowing that meal's absorption ("vegetables first",
     * Shukla et al.). The protocols eat the vegetables 10-15 min before the carbs.
     */
    static final int FIRST_COURSE_WINDOW_MIN = 30;

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

    /** Coverage of the prediction band (matches the empirical twin's likely-range cone). */
    private static final double BAND_CONFIDENCE = 0.9;
    /** Standard-normal z for a central {@link #BAND_CONFIDENCE} interval. */
    private static final double BAND_Z = 1.6449;

    /**
     * Build the full prediction path using the Hovorka ODE model.
     * Parameters are loaded from the user's COB settings.
     *
     * @param currentGlucose         current CGM reading [mmol/L]
     * @param currentTime            client-side "now"
     * @param pastCarbsEntries       carb events from the last 8 h (for gut warm-up)
     * @param pastInsulinDoses       bolus doses from the last 8 h (for IOB curve)
     * @param longActingNotes        long-acting notes from the last
     *                               {@link BasalInsulinResolver#LOOKBACK_HOURS} hours (for EGP and
     *                               the usual daily amount)
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
                ActivityProvider.NONE,
                insulinPrefsService.getLongActingDurationHours(userId),
                Objects.requireNonNullElse(residualProvider.activityGains(userId), ActivityGains.NEUTRAL));
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
                activityProvider,
                insulinPrefsService.getLongActingDurationHours(userId),
                Objects.requireNonNullElse(residualProvider.activityGains(userId), ActivityGains.NEUTRAL));
    }

    /**
     * The dashboard forecast: as the activity-aware overload above, anchored on the CGM reading
     * <b>and its current trend</b> (mmol/L per minute, 0 when unknown). The trend is what lets the
     * forecast start from where plasma glucose is rather than from where the sensor still reads -
     * see {@link InterstitialLagModel}.
     */
    public List<PredictionPointDTO> buildPredictionPath(
            double currentGlucose,
            double currentTrendMmolPerMin,
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
        return integrate(p, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                activityProvider,
                insulinPrefsService.getLongActingDurationHours(userId),
                Objects.requireNonNullElse(residualProvider.activityGains(userId), ActivityGains.NEUTRAL),
                1.0, currentTrendMmolPerMin);
    }

    /**
     * Build the prediction path with pre-built parameters.
     *
     * <p>For callers that built the parameters themselves (calibration replay, backtests).</p>
     *
     * @param customParams           pre-built HovorkaParameters
     * @param currentGlucose         current CGM reading [mmol/L]
     * @param currentTime            client-side "now"
     * @param pastCarbsEntries       carb events from the last 8 h (for gut warm-up)
     * @param pastInsulinDoses       bolus doses from the last 8 h (for IOB curve)
     * @param longActingNotes        long-acting notes from the last
     *                               {@link BasalInsulinResolver#LOOKBACK_HOURS} hours (for EGP and
     *                               the usual daily amount)
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
                ActivityProvider.NONE,
                insulinPrefsService.getLongActingDurationHours(userId));
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
                activityProvider,
                insulinPrefsService.getLongActingDurationHours(userId));
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
                ActivityProvider.NONE,
                BasalInsulinResolver.DEFAULT_DURATION_HOURS);
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
                activityProvider,
                BasalInsulinResolver.DEFAULT_DURATION_HOURS);
    }

    /**
     * Hot-path overload that also takes the duration of action of the user's long-acting insulin
     * [hours], so a calibration replay prices basal coverage exactly as the live forecast does.
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
            ActivityProvider activityProvider,
            double basalDurationHours) {

        return buildPredictionPath(customParams, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                activityProvider, basalDurationHours, ActivityGains.NEUTRAL);
    }

    /**
     * As the hot-path overload above, with per-user {@link ActivityGains} shaping the activity term
     * (gains are clamped; {@link ActivityGains#NEUTRAL} is bit-identical to the un-personalised model).
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
            ActivityProvider activityProvider,
            double basalDurationHours,
            ActivityGains gains) {

        return buildWithParams(customParams, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                activityProvider, basalDurationHours, gains);
    }

    /**
     * As the overload above, with an explicit multiplier on every dose's ISF. This is how a
     * calibration replay tries a candidate {@code isfScale}: the replay runs on the
     * {@link PredictionResidualProvider#NONE} provider, whose scale is 1.0, so the candidate has to
     * arrive as an argument. It multiplies the provider's own scale and the product is clamped once.
     *
     * <p>{@code anchorTrendMmolPerMin} is the CGM trend at the anchor (0 when unknown), so a replay
     * starts each anchor the way the dashboard does.</p>
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
            ActivityProvider activityProvider,
            double basalDurationHours,
            ActivityGains gains,
            double isfScale,
            double anchorTrendMmolPerMin) {

        return integrate(customParams, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                activityProvider, basalDurationHours, gains, isfScale, anchorTrendMmolPerMin);
    }

    /** Core ODE integration with population-default activity gains. */
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
            ActivityProvider activityProvider,
            double basalDurationHours) {
        return buildWithParams(p, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                activityProvider, basalDurationHours, ActivityGains.NEUTRAL);
    }

    /**
     * The live entry to the core integration: no caller-supplied ISF scale, so the twin's scale
     * comes from {@link #residualProvider} alone. Package-private so tests can observe which gains
     * a live overload resolved.
     */
    List<PredictionPointDTO> buildWithParams(
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
            ActivityProvider activityProvider,
            double basalDurationHours,
            ActivityGains gains) {
        return integrate(p, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, userId, pathMinutes,
                activityProvider, basalDurationHours, gains, 1.0, 0.0);
    }

    /** Core ODE integration - shared by every overload. */
    private List<PredictionPointDTO> integrate(
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
            ActivityProvider activityProvider,
            double basalDurationHours,
            ActivityGains gains,
            double callerIsfScale,
            double anchorTrendMmolPerMin) {

        // -- State warm-up -----------------------------------------------------
        // The ODE state is plasma glucose; the anchor is a sensor reading, which trails plasma.
        double plasmaNow = Math.max(G_MIN, Math.min(G_MAX,
                InterstitialLagModel.plasmaFromSensor(currentGlucose, anchorTrendMmolPerMin)));
        HovorkaState state = HovorkaState.steadyState(plasmaNow, p);

        // -- EGP net from long-acting insulin ----------------------------------
        // While sick the reference is the raised basal need, so an unchanged dose covers less of it.
        SickProfile sickProfile = settings == null || settings.getSickProfile() == null
                ? SickProfile.EMPTY : settings.getSickProfile();
        double x3Basal  = basalResolver.resolveEgpSuppression(
                longActingNotes, currentTime, basalDurationHours,
                sickProfile.basalReferenceUnits(
                        basalResolver.usualDailyUnits(longActingNotes, currentTime), currentTime));
        double egp0Abs  = p.egp0();
        double egpNow   = basalResolver.netEgp(p.f01(), egp0Abs, x3Basal);
        // No basal logged, or the newest logged dose is too old to say anything about current
        // coverage -> assume fasting steady state (T1D on continuous unlogged background basal).
        // Without this guard, egpNow = EGP0 >> f01 and glucose rises ~9 mmol/L in 4 h with no COB/IOB.
        if (!basalResolver.hasActiveDose(longActingNotes, currentTime, basalDurationHours)) {
            egpNow = p.f01();
        }
        // Re-parameterise: set egpNet = egpNow (basal-adjusted) and egp0 = egpNow as well, so the
        // ODE's constant EGP term is the basal-adjusted value. Bolus insulin does not suppress EGP
        // separately - its whole measured effect is carried by insulinEffect via the user's ISF.
        HovorkaParameters pAdj = new HovorkaParameters(
                p.vG(), p.f01(), egpNow, egpNow, p.k12(), p.k21(),
                p.tMaxG(), p.aG(), p.isf(), p.weightKg());

        // -- Pre-compute per-dose IOB timelines, each tagged with the ISF that --
        //    was in effect when that dose was administered --------------------
        List<DoseActivity> doseActivities = buildDoseActivities(pastInsulinDoses, currentTime,
                pathMinutes, rapidIob, settings, pAdj.isf(), userId, callerIsfScale);

        // -- Gut: one Dalla Man chain per meal (logged and prospective), each at its own GI and
        //    gut time constant, run ahead of the glucose ODE (the gut does not depend on glucose). --
        MealGutChains.Appearance appearance = buildAppearance(pastCarbsEntries, currentTime, pAdj, settings, pathMinutes);

        // -- Integration loop (1-min steps) -----------------------------------

        List<PredictionPointDTO> points = new ArrayList<>();
        int nextEmit = DENSE_STEP_MIN;

        // CGM measurement model: what the sensor would read, not what plasma does. Seeded with
        // the reading itself, so the emitted curve leaves "now" from the measured value and with
        // the measured trend. Pass-through when the lag is switched off.
        InterstitialLagModel sensor = InterstitialLagModel.startingAt(
                InterstitialLagModel.tauMinutes > 0 ? currentGlucose : state.glucoseMmolL(pAdj));

        // -- Activity modulation (a(t) -> insulin-sensitivity amplification + insulin-independent
        //    uptake). Inert with the NONE provider - that path is bit-identical to the base model. --
        boolean hasActivity = activityProvider != ActivityProvider.NONE;
        ActivityModulation activity = gains.clamped().modulation();
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

            double raNow = appearance.stepMean()[min];

            if (hasActivity) {
                double aInst = activityProvider.intensityAt(currentTime.plusMinutes(min));
                double aSens = activity.stepSensitivity(aInst);
                double sensFactor = activity.insulinSensitivityFactor(aSens);
                double insulinEffectMod = insulinEffect * sensFactor;
                state = odeSolver.stepWithAppearance(state, pAdj, raNow,
                        insulinEffectMod, activity.uptakeRate(aInst));
            } else {
                state = odeSolver.stepWithAppearance(state, pAdj, raNow, insulinEffect, 0.0);
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
                double carbEffect  = appearance.atEnd()[min] * DENSE_STEP_MIN;
                double insulinEff  = -insulinEffect * DENSE_STEP_MIN;

                // Confidence cone from the twin's per-horizon σ: narrow near now, wider further out.
                // The NONE provider (calibration replay) returns σ = 0, so no band is emitted.
                double sd = residualProvider.uncertaintySdMmol(userId, min);
                Double lower = null, upper = null, sdOut = null, level = null;
                if (sd > 0) {
                    double half = BAND_Z * sd;
                    lower = Math.round(Math.max(G_MIN, gAdj - half) * 10.0) / 10.0;
                    upper = Math.round(Math.min(G_MAX, gAdj + half) * 10.0) / 10.0;
                    sdOut = Math.round(sd * 100.0) / 100.0;
                    level = BAND_CONFIDENCE;
                }
                points.add(PredictionPointDTO.builder()
                        .timestamp(pointTime)
                        .predictedGlucose(Math.round(gAdj * 10.0) / 10.0)
                        .rawPredictedGlucose(gAdj)
                        .predictedGlucoseLower(lower)
                        .predictedGlucoseUpper(upper)
                        .uncertaintySd(sdOut)
                        .confidenceLevel(level)
                        .carbAbsorptionEffect(Math.round(carbEffect * 100.0) / 100.0)
                        .insulinActivityEffect(Math.round(insulinEff * 100.0) / 100.0)
                        .absorptionMode("DALLA_MAN_3COMP")
                        .build());

                nextEmit += (min < DENSE_LIMIT_MIN ? DENSE_STEP_MIN : SPARSE_STEP_MIN);
            }
        }

        return points;
    }

    // -- Gut appearance -------------------------------------------------------

    /** Oldest meal age replayed into the gut [min]. */
    private static final int MAX_HISTORY_MIN = 480;

    /**
     * Glucose appearance [mmol/min] for each forecast minute, from every meal in
     * {@code carbsEntries} - logged ones replayed from when they were eaten (up to 8 h back),
     * prospective ones from when they will be. Each meal runs in its own gut chain at its own GI
     * and tMaxG (see {@link MealGutChains}); protein and fat from any of them drive the shared
     * ileal brake.
     */
    private MealGutChains.Appearance buildAppearance(List<CarbsEntry> carbsEntries, LocalDateTime now,
                                     HovorkaParameters p, UserSettingsDTO settings, int pathMinutes) {
        MealGutChains chains = new MealGutChains(gutModel);
        Map<CarbsEntry, Double> fiberByMeal = effectiveFiberByMeal(carbsEntries);
        for (CarbsEntry entry : carbsEntries) {
            if (entry.getTimestamp() == null) continue;
            long minsAgo = minsAgoFromNow(entry.getTimestamp(), now);
            // A past meal eaten A minutes ago enters before the step ending at 1-A; a meal at or
            // after "now" before the step ending at its minute (at least 1).
            int step = minsAgo > 0
                    ? 1 - (int) Math.min(minsAgo, MAX_HISTORY_MIN)
                    : Math.max(1, (int) -minsAgo);
            chains.addProtFat(step, protFatKcal(entry));
            double carbMmol = toCarbMmol(entry, p, settings);
            if (carbMmol > 0) {
                chains.addMeal(new MealGutChains.Meal(step, carbMmol, giOf(entry), tMaxGOf(entry, p, fiberByMeal)));
            }
        }
        return chains.appearance(pathMinutes);
    }

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
     *
     * <p>The digital twin's ISF scale is applied here and nowhere else: the provider's scale (live)
     * times {@code callerIsfScale} (a calibration replay's candidate), clamped once. Neither
     * {@code settings} nor {@code fallbackIsf} may arrive already scaled.</p>
     */
    private List<DoseActivity> buildDoseActivities(
            List<InsulinDose> doses,
            LocalDateTime now,
            int pathMinutes,
            RapidInsulinIobParameters rapidIob,
            UserSettingsDTO settings,
            double fallbackIsf,
            UUID userId,
            double callerIsfScale) {

        int size = pathMinutes + 2;
        double isfScale = TwinScales.clamp(
                TwinScales.clamp(residualProvider.isfScale(userId)) * TwinScales.clamp(callerIsfScale));
        List<DoseActivity> activities = new ArrayList<>();
        for (InsulinDose dose : doses) {
            if (dose.getTimestamp() == null || dose.getUnits() == null) continue;
            double minsAgoDose = minsAgoFromNow(dose.getTimestamp(), now);
            // The dose's own insulin curve when it records one, else the user's current insulin.
            double diaHours    = dose.hasOwnCurve() ? dose.getDiaHours()    : rapidIob.diaHours();
            double peakMinutes = dose.hasOwnCurve() ? dose.getPeakMinutes() : rapidIob.peakMinutes();
            double[] iob = new double[size];
            for (int m = 0; m < size; m++) {
                // Elapsed time since this dose at prediction step m. For past doses
                // (minsAgoDose > 0), more time has passed by step m, so IOB must keep
                // decaying - NOT fold back toward the full dose. For prospective doses
                // (minsAgoDose < 0), this is negative until m reaches the delivery
                // minute; iobOpenApsExponential returns 0 for negative input.
                double minsAgoAtStep = minsAgoDose + m;
                iob[m] = InsulinCalculatorService.iobOpenApsExponential(
                        dose.getUnits(), minsAgoAtStep, diaHours, peakMinutes);
            }
            double isf = resolveIsf(settings, fallbackIsf, dose.getTimestamp()) * isfScale;
            activities.add(new DoseActivity(iob, isf));
        }
        return activities;
    }

    /**
     * Resolves the ISF [mmol/L per unit] in effect at {@code time}: the user's manual
     * per-meal-window override (isfBreakfast/isfLunch/isfDinner/isfNight) if one applies to
     * {@code time}'s meal window, otherwise {@code fallbackIsf} (the unscaled ISF of
     * {@link HovorkaParameters#isf()}).
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

    /**
     * Converts a {@link CarbsEntry} to gut mmol input using the Hovorka formula:
     * <pre>
     *   carbMmol = grams × A_G / 0.18
     * </pre>
     * where 0.18 is the molar mass of glucose (g/mmol, MW = 180 g/mol / 1000).
     *
     * <p>{@code A_G} is the per-user meal magnitude from the insulin-to-carb ratio
     * ({@link HovorkaParameterService#mealMagnitude}), <b>not</b> a bioavailability factor.
     * Physiological carb bioavailability (~0.90) is applied exactly once, downstream, by
     * {@link DallaManGutModel#ra} ({@code F = 0.90}): the meal dose D = grams × A_G / 0.18 enters
     * the stomach, and only {@code F} of it ultimately appears in blood.</p>
     */
    private double toCarbMmol(CarbsEntry entry, HovorkaParameters p, UserSettingsDTO settings) {
        if (entry.getCarbs() == null || entry.getCarbs() <= 0) return 0.0;
        return entry.getCarbs() * p.aG() * mealWindowFactor(entry.getTimestamp(), p, settings) / 0.18;
    }

    /** Bounds on {@link #mealWindowFactor}: settings this far apart are a mistake, not a physiology. */
    static final double MEAL_WINDOW_FACTOR_MIN = 0.33;
    static final double MEAL_WINDOW_FACTOR_MAX = 3.0;

    /**
     * Sizes a meal by the ISF and carb ratio of its own meal window. {@code A_G} makes the single
     * {@code gramsPerUnit} grams raise glucose as much as one unit lowers it at the base ISF, but a
     * dose acts at the ISF of the window it is taken in and is priced by that window's ratio. The
     * factor is (window ISF / base ISF) x (single ratio / window ratio), so a meal dosed exactly by
     * its window's ratio forecasts flat (lunch ISF 1.47 vs base 1.0 used to make every ratio-priced
     * lunch dose look ~47% too big; a breakfast ratio of 6 g/U vs 10 g/U means less carb per unit,
     * i.e. the meal pushes glucose up 10/6 times as much per gram).
     *
     * <p>Neither the illness multiplier nor the twin's scales enter: illness changes how insulin
     * acts, not how much glucose a gram of carbs adds. When the single ratio did not shape
     * {@code A_G} (no usable grams-per-unit) but the window ratio is usable - the calculator then
     * prices the meal by the window ratio alone - the ratio part sizes the meal from the window
     * ratio itself: {@code mealMagnitude(baseIsf, VG, windowRatio) / A_G}. 1.0 when neither ratio is
     * usable; an unusable window ISF or window ratio otherwise drops only its own part. The result is
     * clamped to [{@link #MEAL_WINDOW_FACTOR_MIN}, {@link #MEAL_WINDOW_FACTOR_MAX}].</p>
     */
    static double mealWindowFactor(LocalDateTime mealTime, HovorkaParameters p, UserSettingsDTO settings) {
        if (settings == null || mealTime == null) return 1.0;
        Double single = settings.getGramsPerUnit();
        boolean singleUsable = HovorkaParameterService.usableGramsPerUnit(single);
        Double windowRatio = settings.getWindowGramsPerUnit(mealTime);
        boolean windowUsable = HovorkaParameterService.usableGramsPerUnit(windowRatio);
        if (!singleUsable && !windowUsable) return 1.0;
        double isfPart = 1.0;
        Double windowIsf = settings.getWindowIsf(mealTime);
        double base = p.isf();
        if (windowIsf != null && Double.isFinite(windowIsf) && windowIsf > 0
                && Double.isFinite(base) && base > 0) {
            isfPart = windowIsf / base;
        }
        double ratioPart = 1.0;
        if (singleUsable && windowUsable) {
            ratioPart = single / windowRatio;
        } else if (windowUsable && p.aG() > 0) {
            ratioPart = HovorkaParameterService.mealMagnitude(p.isf(), p.vG(), windowRatio) / p.aG();
        }
        return Math.max(MEAL_WINDOW_FACTOR_MIN, Math.min(MEAL_WINDOW_FACTOR_MAX, isfPart * ratioPart));
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
     * user's own tMaxG slowed by the meal's fiber (its own plus any first course's - see
     * {@link #effectiveFiberByMeal}) through the fiber viscosity factor.
     * Shared by the warm-up replay and the future timeline for the same reason as {@link #giOf} -
     * a meal logged one minute ago and one logged one minute ahead must absorb identically.
     * No fiber returns {@code p.tMaxG()} exactly, so fiber-free curves are unchanged.
     */
    private double tMaxGOf(CarbsEntry entry, HovorkaParameters p, Map<CarbsEntry, Double> fiberByMeal) {
        if (RescueCarbProfile.isRescue(entry.getAbsorptionMode())) {
            return HovorkaParameterService.rescueTMaxG();
        }
        double fiber = fiberByMeal.getOrDefault(entry, 0.0);
        return fiber > 0 ? p.tMaxG() * MacroNutrientGastricModel.fiberViscosityFactor(fiber) : p.tMaxG();
    }

    /**
     * Fiber [g] slowing each carb meal: its own plus that of every other entry eaten in the
     * {@link #FIRST_COURSE_WINDOW_MIN} minutes before it (a salad before the pasta). Keyed by
     * identity - entries are not value objects. Rescue carbs keep their own fixed profile.
     */
    private static Map<CarbsEntry, Double> effectiveFiberByMeal(List<CarbsEntry> entries) {
        Map<CarbsEntry, Double> fiberByMeal = new IdentityHashMap<>();
        for (CarbsEntry meal : entries) {
            if (meal.getTimestamp() == null || meal.getCarbs() == null || meal.getCarbs() <= 0) continue;
            double fiber = fiberOf(meal);
            for (CarbsEntry course : entries) {
                if (course == meal || course.getTimestamp() == null) continue;
                long minutesBefore = Duration.between(course.getTimestamp(), meal.getTimestamp()).toMinutes();
                if (minutesBefore > 0 && minutesBefore <= FIRST_COURSE_WINDOW_MIN) {
                    fiber += fiberOf(course);
                }
            }
            if (fiber > 0) fiberByMeal.put(meal, fiber);
        }
        return fiberByMeal;
    }

    private static double fiberOf(CarbsEntry entry) {
        return entry.getFiber() != null ? Math.max(0.0, entry.getFiber()) : 0.0;
    }

    /**
     * A meal's braking load [kcal] - the GLP-1 driver behind the ileal brake. This is the
     * protein+fat energy beyond the reference meal's share for the meal's carbs
     * ({@link HovorkaOdeSolver#brakeKcal}), not the meal's whole protein+fat energy; it is 0 for
     * a meal at or under that share.
     * Shared by the warm-up replay and the future timeline, same reason as {@link #giOf}.
     */
    private double protFatKcal(CarbsEntry entry) {
        return HovorkaOdeSolver.brakeKcal(
                entry.getCarbs()   != null ? entry.getCarbs()   : 0.0,
                entry.getProtein() != null ? entry.getProtein() : 0.0,
                entry.getFat()     != null ? entry.getFat()     : 0.0);
    }

    /** Round to 1 decimal place [mmol/L] - matches the predictedGlucose precision. */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
