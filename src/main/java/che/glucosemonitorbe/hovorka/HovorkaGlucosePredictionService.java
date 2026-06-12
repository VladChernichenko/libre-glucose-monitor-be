package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.service.COBSettingsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 *   <li>Emit {@link PredictionPointDTO} every 5 min (0–4 h) or 10 min (4–8 h).</li>
 * </ol>
 *
 * <h3>Insulin effect modelling</h3>
 * <p>Rather than tracking plasma insulin concentration through S1→S2→I_plasma compartments,
 * we compute the bolus IOB activity rate directly from the proven OpenAPS exponential curve:</p>
 * <pre>
 *   iobActivityRate(t) = max(0, IOB(t) - IOB(t+1))    [units/min]
 *   insulinEffect(t)   = ISF × VG × iobActivityRate(t) [mmol/min]
 * </pre>
 * <p>This preserves exact IOB pharmacokinetics while avoiding unit-conversion errors in
 * the full Hovorka subcutaneous absorption chain (S1→S2→I_plasma).</p>
 *
 * <h3>EGP modelling</h3>
 * <p>Net EGP is calibrated by {@link BasalInsulinResolver} from the user's long-acting notes.
 * At steady state with therapeutic basal, egpNet = F01 (hepatic output = brain/RBC uptake).
 * If basal wanes, egpNet rises above F01 → fasting hyperglycaemia in the prediction.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HovorkaGlucosePredictionService {

    // ── Emission schedule (matches existing OpenAPS path) ─────────────────────
    private static final int DENSE_STEP_MIN   = 5;
    private static final int SPARSE_STEP_MIN  = 10;
    private static final int DENSE_LIMIT_MIN  = 240;

    private final HovorkaParameterService       paramService;
    private final HovorkaOdeSolver              odeSolver;
    private final BasalInsulinResolver          basalResolver;
    private final UserInsulinPreferencesService insulinPrefsService;
    private final DallaManGutModel              gutModel;
    private final COBSettingsService            cobSettingsService;

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
     * @param pathMinutes            total prediction horizon [min] — 240 or 480
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
        COBSettingsDTO settings  = cobSettingsService.getCOBSettings(userId);
        return buildWithParams(p, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, pathMinutes);
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
        COBSettingsDTO settings = cobSettingsService.getCOBSettings(userId);
        return buildWithParams(customParams, rapidIob, settings, currentGlucose, currentTime,
                pastCarbsEntries, pastInsulinDoses, longActingNotes, pathMinutes);
    }

    /**
     * Core ODE integration — shared by both public overloads.
     */
    private List<PredictionPointDTO> buildWithParams(
            HovorkaParameters p,
            RapidInsulinIobParameters rapidIob,
            COBSettingsDTO settings,
            double currentGlucose,
            LocalDateTime currentTime,
            List<CarbsEntry> pastCarbsEntries,
            List<InsulinDose> pastInsulinDoses,
            List<Note> longActingNotes,
            int pathMinutes) {

        // ── State warm-up ─────────────────────────────────────────────────────
        HovorkaState state = buildWarmState(currentGlucose, pastCarbsEntries, currentTime, p);

        // ── EGP net from long-acting insulin ──────────────────────────────────
        double x3Basal  = basalResolver.resolveEgpSuppression(longActingNotes, currentTime);
        double egp0Abs  = HovorkaParameters.EGP0_PER_KG * p.weightKg();
        double egpNow   = basalResolver.netEgp(p.f01(), egp0Abs, x3Basal);
        // No basal logged → assume fasting steady state (T1D on continuous unlogged background basal).
        // Without this guard, egpNow = EGP0 >> f01 and glucose rises ~9 mmol/L in 4 h with no COB/IOB.
        if (longActingNotes == null || longActingNotes.isEmpty()) {
            egpNow = p.f01();
        }
        // Re-parameterise: swap egpNet in p with the basal-adjusted value
        HovorkaParameters pAdj = new HovorkaParameters(
                p.vG(), p.f01(), egpNow, p.k12(), p.k21(),
                p.tMaxG(), p.aG(), p.isf(), p.weightKg());

        // ── Pre-compute per-dose IOB timelines, each tagged with the ISF that ──
        //    was in effect when that dose was administered ────────────────────
        List<DoseActivity> doseActivities = buildDoseActivities(pastInsulinDoses, currentTime,
                pathMinutes, rapidIob, settings, pAdj.isf());

        // ── Future carb timeline (prospective notes already in pastCarbsEntries
        //    with future timestamps — carbs at t ≤ now were in the warm-up) ───
        Map<Integer, Double> futureCarbs = buildFutureCarbTimeline(
                pastCarbsEntries, currentTime, pAdj);

        // ── Integration loop (1-min steps) ───────────────────────────────────
        // Pre-compute effective K_ABS once — same tMaxG for the whole prediction.
        double kAbsEff = DallaManGutModel.effectiveKAbs(pAdj.tMaxG());

        List<PredictionPointDTO> points = new ArrayList<>();
        int nextEmit = DENSE_STEP_MIN;

        for (int min = 1; min <= pathMinutes; min++) {
            // insulinEffect: mmol of glucose removed from Q1 per minute, summed across all
            // active doses. effectiveInsulinVolume = 2×VG corrects for the 2-compartment
            // distribution factor (see HovorkaParameters.effectiveInsulinVolume() for
            // derivation). Each dose's ISF was resolved once, from the time the dose was
            // administered — a manual isfBreakfast/isfLunch/isfDinner override applies to a
            // dose's entire activity curve if the dose was given in that window, even once
            // most of its activity plays out after the window ends (e.g. a dinner-time
            // correction bolus peaking after 22:00 still uses isfDinner).
            double insulinEffect = 0.0;
            for (DoseActivity dose : doseActivities) {
                // IOB activity rate: how many units/min this dose is "working" during the
                // step that advances state from t=now+(min-1) to t=now+min, i.e. the IOB
                // decay during [min-1, min] — NOT [min, min+1].
                double iobActivityRate = iobActivityRate(dose.iobTimeline(), min - 1);
                insulinEffect += dose.isf() * pAdj.effectiveInsulinVolume() * iobActivityRate;
            }

            // Future carbs delivered to gut D1 at this minute [mmol]
            double carbMmol = futureCarbs.getOrDefault(min, 0.0);

            state = odeSolver.step(state, pAdj, carbMmol, insulinEffect);

            if (min == nextEmit) {
                double gPred = state.glucoseMmolL(pAdj);
                double carbEffect  = gutModel.ra(state.qgut(), kAbsEff) * DENSE_STEP_MIN;
                double insulinEff  = -insulinEffect * DENSE_STEP_MIN;

                points.add(PredictionPointDTO.builder()
                        .timestamp(currentTime.plusMinutes(min))
                        .predictedGlucose(Math.round(gPred * 10.0) / 10.0)
                        .carbAbsorptionEffect(Math.round(carbEffect * 100.0) / 100.0)
                        .insulinActivityEffect(Math.round(insulinEff * 100.0) / 100.0)
                        .absorptionMode("DALLA_MAN_3COMP")
                        .build());

                nextEmit += (min < DENSE_LIMIT_MIN ? DENSE_STEP_MIN : SPARSE_STEP_MIN);
            }
        }

        return points;
    }

    // ── State warm-up ─────────────────────────────────────────────────────────

    /**
     * Initialises the extended state reflecting the current physiological state.
     *
     * <ul>
     *   <li>Q1, Q2 — from current CGM glucose (steady-state approximation).</li>
     *   <li>Qsto1, Qsto2, Qgut — by replaying each past meal through the
     *       Dalla Man gut ODE up to "now". This gives accurate nonlinear
     *       absorption state without shortcuts.</li>
     * </ul>
     */
    private HovorkaState buildWarmState(
            double currentGlucose,
            List<CarbsEntry> pastCarbs,
            LocalDateTime now,
            HovorkaParameters p) {

        HovorkaState ss = HovorkaState.steadyState(currentGlucose, p);

        // Same macro-modulated drain rate as the forward RK4 integration (HovorkaOdeSolver),
        // so a meal's Qgut carries over consistently across the warm-up/forward boundary.
        double kAbsEff = DallaManGutModel.effectiveKAbs(p.tMaxG());

        // Collect past meals (delivered before "now") as age-in-minutes → carb mmol.
        // We replay ALL of them through ONE shared gut chain in chronological order — not
        // isolated per-meal chains — so the k_empt D reference is refreshed to the stomach
        // load at each ingestion exactly like HovorkaOdeSolver.step. This makes stacked
        // history meals (e.g. snack then dinner) carry the same emptying dynamics into the
        // forward integration as the forward path itself, and keeps a single fresh meal
        // consistent with a continuous run that started at the meal.
        Map<Integer, Double> mealsByAge = new HashMap<>();
        int oldestAge = 0;
        for (CarbsEntry entry : pastCarbs) {
            if (entry.getTimestamp() == null) continue;
            long minsAgo = minsAgoFromNow(entry.getTimestamp(), now);
            if (minsAgo <= 0) continue;
            int ageMin = (int) Math.min(minsAgo, 480);
            double carbMmol = toCarbMmol(entry, p);
            if (carbMmol <= 0) continue;
            mealsByAge.merge(ageMin, carbMmol, Double::sum);
            oldestAge = Math.max(oldestAge, ageMin);
        }

        if (mealsByAge.isEmpty()) {
            return ss;
        }

        double qsto1 = 0.0, qsto2 = 0.0, qgut = 0.0, dRef = 0.0;
        // Tick down from the oldest meal's age to 1 minute ago. At each tick, ingest any
        // meal whose age equals the current tick, then advance the gut ODE by one minute.
        for (int age = oldestAge; age >= 1; age--) {
            Double carbMmol = mealsByAge.get(age);
            if (carbMmol != null) {
                qsto1 += carbMmol;
                dRef   = qsto1 + qsto2;   // refresh D = stomach load, like step()
            }
            double qsto  = qsto1 + qsto2;
            double kempt = dRef > 0 ? gutModel.kEmpt(qsto, dRef) : 0.0;
            double dQsto1 = -DallaManGutModel.K_GRI * qsto1;
            double dQsto2 = DallaManGutModel.K_GRI * qsto1 - kempt * qsto2;
            double dQgut  = kempt * qsto2 - kAbsEff * qgut;
            qsto1 = Math.max(0.0, qsto1 + dQsto1);
            qsto2 = Math.max(0.0, qsto2 + dQsto2);
            qgut  = Math.max(0.0, qgut  + dQgut);
        }

        HovorkaState warm = new HovorkaState(
                ss.q1(), ss.q2(), qsto1, qsto2, qgut, 0.0, dRef);

        log.debug("Dalla Man warm state: G={}mmol/L Q1={} Qsto1={} Qsto2={} Qgut={}",
                currentGlucose, warm.q1(), warm.qsto1(), warm.qsto2(), warm.qgut());
        return warm;
    }

    // ── Per-dose IOB timelines ───────────────────────────────────────────────

    /**
     * A single insulin dose's IOB [units] timeline (index 0 = current time, index m =
     * currentTime + m minutes) paired with the ISF [mmol/L per unit] resolved from the
     * moment the dose was administered.
     */
    private record DoseActivity(double[] iobTimeline, double isf) {}

    /**
     * Pre-computes, for each past or prospective dose, its IOB timeline from the OpenAPS
     * curve and the ISF in effect when it was administered: the user's manual
     * isfBreakfast/isfLunch/isfDinner override for the dose's own meal window, or
     * {@code fallbackIsf} if none applies (e.g. doses given overnight).
     */
    private List<DoseActivity> buildDoseActivities(
            List<InsulinDose> doses,
            LocalDateTime now,
            int pathMinutes,
            RapidInsulinIobParameters rapidIob,
            COBSettingsDTO settings,
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
                // decaying — NOT fold back toward the full dose. For prospective doses
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
     * per-meal-window override (isfBreakfast/isfLunch/isfDinner) if one applies to
     * {@code time}'s meal window, otherwise {@code fallbackIsf} (the Hovorka-calibrated
     * ISF from {@link HovorkaParameterService#buildForUser}).
     */
    private double resolveIsf(COBSettingsDTO settings, double fallbackIsf, LocalDateTime time) {
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
     * IOB activity rate at minute m [units/min] — the rate at which IOB is being consumed.
     * Computed as a forward difference: max(0, IOB[m] - IOB[m+1]).
     */
    private double iobActivityRate(double[] iobTimeline, int m) {
        if (m + 1 >= iobTimeline.length) return 0.0;
        return Math.max(0.0, iobTimeline[m] - iobTimeline[m + 1]);
    }

    // ── Future carb timeline ──────────────────────────────────────────────────

    /**
     * Builds a map of minute → carb mmol for future events only.
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
            if (minsAgo > 0) continue; // past — captured in warm-up

            // Future or current event: minute = |minsAgo|.
            // Clamp to 1: minsAgo=0 (meal logged at exactly "now") must still enter the ODE loop,
            // which starts at min=1 — storing at key=0 would cause the meal to be silently dropped.
            int futureMin = Math.max(1, (int) Math.abs(minsAgo));
            double carbMmol = toCarbMmol(entry, p);
            if (carbMmol > 0) {
                timeline.merge(futureMin, carbMmol, Double::sum);
            }
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
}
