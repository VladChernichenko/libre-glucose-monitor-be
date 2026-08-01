package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictRequest;
import che.glucosemonitorbe.dto.PredictResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.MacroNutrientGastricModel;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
import che.glucosemonitorbe.service.prebolus.PreBolusContext;
import che.glucosemonitorbe.service.prebolus.PreBolusResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the {@code POST /api/predict} prediction pipeline.
 *
 * <h3>Pipeline steps</h3>
 * <ol>
 *   <li>Load 8-hour carb and insulin history from the database.</li>
 *   <li>Build Hovorka parameters for the user; override tMaxG via
 *       {@link MacroNutrientGastricModel} (Elashoff + fiber viscosity).</li>
 *   <li>Resolve whether a pre-bolus is already in flight ({@link PreBolusResolver}), then
 *       take one of two branches.</li>
 *   <li><b>Live-timer branch</b> - a logged pre-bolus is in flight. Its dose is already in
 *       the loaded history and is not added again; the meal stays at {@code now}; no
 *       optimisation runs. The response reports the measured
 *       {@code observedPreBolusMinutes} and leaves {@code preBolusMinutes} null.</li>
 *   <li><b>Advisory branch</b> - no pre-bolus in flight. The bolus is placed at {@code now}
 *       and the prospective meal (plus its protein-gluconeogenesis tail) at
 *       {@code now + pause} for each candidate pause [0, 5, 10, 15, 20, 25, 30] min. Each
 *       candidate simulates {@code horizon + pause} minutes and is scored over that whole
 *       window from the injection onward - including the pre-meal interval, where an
 *       over-long pre-bolus causes its hypo - as a trapezoidal time-weighted mean of the
 *       hypo-weighted squared deviation from 5.5 mmol/L (see
 *       {@link #timeWeightedMeanCost}). The response reports the winning pause as
 *       {@code preBolusMinutes} and leaves {@code observedPreBolusMinutes} null.</li>
 *   <li>Return the final prediction curve - on the advisory branch it spans
 *       {@code horizon + recommendedPause}, so it is the winning candidate's own curve -
 *       together with the pause fields and the bolus strategy.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlucosePredictService {

    private static final int     DEFAULT_HORIZON_MIN = 300;
    private static final double  TARGET_GLUCOSE      = 5.5;   // mmol/L
    private static final int[]   PREBOLUS_CANDIDATES = {0, 5, 10, 15, 20, 25, 30};

    /** Predicted glucose below this [mmol/L] is treated as a hypo and penalised hard. */
    private static final double  HYPO_THRESHOLD       = 3.9;
    /** Extra quadratic penalty weight applied per (3.9 − G)² below the hypo threshold. */
    private static final double  HYPO_PENALTY_WEIGHT  = 50.0;
    /** Mild multiplier for points between the hypo threshold and target (low side of 5.5). */
    private static final double  LOW_SIDE_WEIGHT      = 2.0;

    // -- Protein gluconeogenesis (PGN) slow-glucose modelling ------------------
    /** Onset for protein gluconeogenesis: protein GNG peaks at 3-4h, onset at 2h. */
    private static final int    PGN_ONSET_MIN   = 120;
    /**
     * Grams of slow-carb-equivalent glucose per gram of protein (Warsaw Protocol adapted):
     * 4 kcal/g ÷ 100 kcal/FPU × 10 g carb/FPU = 0.40 g/g.
     * Fat contribution removed — fat's gastric delay is already in MacroNutrientGastricModel.
     */
    private static final double PGN_CARB_FACTOR = 0.40;
    /** Minimum PGN-equiv carbs [g] to emit a secondary entry (≈ 0.2 FPU threshold). */
    private static final double FPU_MIN_EQUIV_G  = 2.0;

    /** Units of disagreement tolerated between the request dose and the logged note. */
    private static final double DOSE_MATCH_TOLERANCE_U = 0.05;

    private final HovorkaGlucosePredictionService hovorkaService;
    private final HovorkaParameterService         paramService;
    private final UserService                     userService;
    private final NoteRepository                  noteRepository;
    private final NoteToCarbsEntryMapper           noteToCarbsEntryMapper;
    private final PreBolusResolver                     preBolusResolver;

    /**
     * Run the prediction pipeline and return the response.
     *
     * @param req       request with current glucose, bolus dose, and macros
     * @param username  authenticated user (from Spring Security principal)
     */
    @Transactional(readOnly = true)
    public PredictResponse predict(PredictRequest req, String username) {
        LocalDateTime now    = LocalDateTime.now();
        UUID          userId = userService.getUserByUsername(username).getId();

        // -- 1. Load history ---------------------------------------------------
        List<Note> recentNotes     = loadRecentNotes(userId, now);
        List<Note> longActingNotes = loadLongActingNotes(userId, now);

        List<CarbsEntry>  pastCarbs = toCarbsEntries(recentNotes);
        List<InsulinDose> pastDoses = toInsulinDoses(recentNotes, userId);

        // -- 2. Build macro-modulated Hovorka params ---------------------------
        double carbsG   = safe(req.getCarbs());
        double proteinG = safe(req.getProtein());
        double fatG     = safe(req.getFat());
        double fiberG   = safe(req.getFiber());

        HovorkaParameters baseParams = paramService.buildForUser(userId);

        double tMaxGMod = (carbsG + proteinG + fatG > 0)
                ? MacroNutrientGastricModel.computeTMaxG(
                        carbsG, proteinG, fatG, fiberG,
                        HovorkaParameterService.HALF_LIFE_TO_TMAX_G)
                : baseParams.tMaxG();

        HovorkaParameters mealParams = new HovorkaParameters(
                baseParams.vG(), baseParams.f01(), baseParams.egpNet(), baseParams.egp0(),
                baseParams.k12(), baseParams.k21(),
                tMaxGMod, baseParams.aG(), baseParams.isf(), baseParams.weightKg());

        int horizon = req.getHorizonMinutes() != null
                ? Math.max(60, Math.min(480, req.getHorizonMinutes()))
                : DEFAULT_HORIZON_MIN;

        // -- 3. Prospective meal + protein-gluconeogenesis tail, anchored to the meal --
        List<CarbsEntry> carbsWithMeal = withProspectiveMeal(pastCarbs, req, userId, now);

        // -- 4. Pre-bolus: measured if one is in flight, otherwise recommended -----
        double insulinDose = safe(req.getInsulinDose());
        Optional<PreBolusContext> preBolus =
                preBolusResolver.resolve(req.getInsulinLoggedAt(), recentNotes, now);

        Integer recommendedPause = null;
        Integer observedPause    = null;
        List<InsulinDose> finalDoses = new ArrayList<>(pastDoses);

        if (preBolus.isPresent()) {
            PreBolusContext ctx = preBolus.get();
            observedPause = ctx.elapsedMinutes();
            if (insulinDose > 0 && Math.abs(insulinDose - ctx.units()) > DOSE_MATCH_TOLERANCE_U) {
                log.warn("predict user={} request insulinDose={} disagrees with logged pre-bolus units={}; using the note",
                        userId, insulinDose, ctx.units());
            }
            // The dose is already in pastDoses. Adding it again would double-count it.
        } else {
            int bestPause = optimisePreBolus(
                    req.getCurrentGlucose(), now,
                    pastCarbs, pastDoses, longActingNotes,
                    userId, mealParams, req, insulinDose, horizon);
            recommendedPause = bestPause;
            carbsWithMeal    = withProspectiveMeal(pastCarbs, req, userId, now.plusMinutes(bestPause));
            if (insulinDose > 0) {
                // Bolus now, meal after `bestPause` minutes - that is what a pre-bolus is.
                finalDoses.add(InsulinDose.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .units(insulinDose)
                        .type(InsulinDose.InsulinType.BOLUS)
                        .timestamp(now)
                        .build());
            }
        }

        // -- 5. Final simulation ----------------------------------------------
        // On the advisory path the meal sits at now + recommendedPause, so simulating only
        // `horizon` would return a curve ending `recommendedPause` minutes short of every
        // window the optimiser scored - at the clamp floor (horizon 60, pause 30) the curve
        // would stop before the meal it was asked about had even peaked. Extending by the
        // pause makes the returned curve the winning candidate's own curve. On the live-timer
        // path recommendedPause is null and the meal is already at now, so `horizon` stands.
        int finalPathMinutes = horizon + (recommendedPause != null ? recommendedPause : 0);

        List<PredictionPointDTO> curve = hovorkaService.buildPredictionPath(
                mealParams,
                req.getCurrentGlucose(), now,
                carbsWithMeal, finalDoses, longActingNotes,
                userId, finalPathMinutes);

        double betaWeighted = MacroNutrientGastricModel.weightedBeta(carbsG, proteinG, fatG);
        String strategy     = MacroNutrientGastricModel.bolusStrategy(fatG, proteinG);

        log.debug("predict user={} tMaxG={} beta={} recommendedPause={} observedPause={} strategy={}",
                userId, tMaxGMod, betaWeighted, recommendedPause, observedPause, strategy);

        return PredictResponse.builder()
                .curve(curve)
                .preBolusMinutes(recommendedPause)
                .observedPreBolusMinutes(observedPause)
                .bolusStrategy(strategy)
                .tMaxGUsed(Math.round(tMaxGMod * 10.0) / 10.0)
                .betaWeighted(Math.round(betaWeighted * 100.0) / 100.0)
                .build();
    }

    // -- Pre-bolus optimisation ------------------------------------------------

    /**
     * Finds the pre-bolus pause [min] from {@link #PREBOLUS_CANDIDATES} that minimises the
     * time-weighted mean deviation from 5.5 mmol/L (see {@link #timeWeightedMeanCost}),
     * scored over the entire simulated window from the bolus (at {@code now}) through
     * {@code horizon} minutes past that candidate's own meal time.
     *
     * <p>Runs at most {@code PREBOLUS_CANDIDATES.length} ODE simulations - each is
     * a few milliseconds, so the total overhead is negligible.</p>
     */
    private int optimisePreBolus(
            double currentGlucose,
            LocalDateTime now,
            List<CarbsEntry> history,
            List<InsulinDose> baseDoses,
            List<Note> longActingNotes,
            UUID userId,
            HovorkaParameters params,
            PredictRequest req,
            double insulinDose,
            int horizon) {

        if (insulinDose <= 0) return 0;

        int    bestPause = 0;
        double bestCost  = Double.MAX_VALUE;

        for (int pause : PREBOLUS_CANDIDATES) {
            LocalDateTime mealTime = now.plusMinutes(pause);

            // Bolus now, meal after `pause` minutes - that is what a pre-bolus is.
            List<InsulinDose> doses = new ArrayList<>(baseDoses);
            doses.add(InsulinDose.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .units(insulinDose)
                    .type(InsulinDose.InsulinType.BOLUS)
                    .timestamp(now)
                    .build());

            List<CarbsEntry> entries = withProspectiveMeal(history, req, userId, mealTime);

            // Extend the simulation by `pause` so every candidate's post-meal excursion is
            // fully covered by the same `horizon`-length window past its own meal time.
            List<PredictionPointDTO> sim = hovorkaService.buildPredictionPath(
                    params, currentGlucose, now,
                    entries, doses, longActingNotes, userId, horizon + pause);

            // Score every emitted point from the bolus (now) onward - NOT just from the meal.
            // The interval [now, mealTime) is exactly where an over-long pre-bolus does its
            // damage (insulin acting with no carbs yet), and that interval grows with the
            // pause, so excluding it would weaken the hypo penalty precisely for the most
            // aggressive candidates.
            double cost = timeWeightedMeanCost(sim, currentGlucose);

            if (cost < bestCost) {
                bestCost  = cost;
                bestPause = pause;
            }
        }
        return bestPause;
    }

    /**
     * Trapezoidal time-weighted mean of {@link #pointPenalty} over a candidate's window:
     * {@code Σ ½(cᵢ + cᵢ₊₁)·Δtᵢ / (t_last − t_first)}.
     *
     * <p>Time-weighted rather than per-sample. The ODE grid is not uniform - it emits every
     * 5 minutes while the elapsed minute is below 240 and every 10 minutes beyond - so a
     * per-sample mean silently counts a point in the sparse tail as worth the same as a point
     * in the dense head, i.e. half its true duration. Because the window is
     * {@code horizon + pause} long, the dense/sparse mix differs per candidate, so the
     * effective weighting would shift with the pause and let a candidate win on emission
     * arithmetic rather than on the modelled glucose. Weighting by Δt removes both the
     * sample-count and the density dependence: the score is the mean penalty per minute.</p>
     *
     * <p>A window with fewer than two usable points, or one whose points span no time, scores
     * {@link Double#MAX_VALUE} rather than 0.0 - an empty or degenerate window must never win.</p>
     *
     * @param sim             the candidate's emitted curve
     * @param fallbackGlucose value substituted for points with a null predicted glucose
     */
    private double timeWeightedMeanCost(List<PredictionPointDTO> sim, double fallbackGlucose) {
        if (sim == null || sim.size() < 2) {
            return Double.MAX_VALUE;
        }
        List<PredictionPointDTO> pts = sim.stream()
                .filter(p -> p.getTimestamp() != null)
                .sorted(Comparator.comparing(PredictionPointDTO::getTimestamp))
                .toList();
        if (pts.size() < 2) {
            return Double.MAX_VALUE;
        }

        double spanMin = minutesBetween(pts.get(0).getTimestamp(),
                                        pts.get(pts.size() - 1).getTimestamp());
        if (spanMin <= 0) {
            return Double.MAX_VALUE;
        }

        double integral = 0.0;
        double prevCost = pointPenalty(pts.get(0), fallbackGlucose);
        for (int i = 1; i < pts.size(); i++) {
            double cost = pointPenalty(pts.get(i), fallbackGlucose);
            double dt   = minutesBetween(pts.get(i - 1).getTimestamp(), pts.get(i).getTimestamp());
            integral += 0.5 * (prevCost + cost) * dt;
            prevCost = cost;
        }
        return integral / spanMin;
    }

    /**
     * Clinical penalty for a single predicted point: squared deviation from
     * {@link #TARGET_GLUCOSE}, asymmetrically weighted.
     *
     * <p>An aggressive pre-bolus that drives a predicted hypo is far more dangerous than mild
     * residual hyperglycaemia, so a symmetric cost could happily recommend a pause that bottoms
     * out at 3.0 mmol/L.</p>
     */
    private double pointPenalty(PredictionPointDTO pt, double fallbackGlucose) {
        double g    = pt.getPredictedGlucose() != null ? pt.getPredictedGlucose() : fallbackGlucose;
        double err  = g - TARGET_GLUCOSE;
        double base = err * err;
        if (g < HYPO_THRESHOLD) {
            double below = HYPO_THRESHOLD - g;
            base += HYPO_PENALTY_WEIGHT * below * below;
        } else if (g < TARGET_GLUCOSE) {
            base *= LOW_SIDE_WEIGHT;
        }
        return base;
    }

    /** Fractional minutes between two instants - the ODE grid is not always whole-minute. */
    private static double minutesBetween(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toNanos() / 60_000_000_000.0;
    }

    /**
     * Returns {@code history} plus the prospective meal at {@code mealTime} and, when the
     * protein load warrants it, a slow-carb gluconeogenesis entry at
     * {@code mealTime + onset}.
     *
     * <p>Both entries are anchored to the meal rather than to "now", so a candidate
     * pre-bolus pause shifts them together.</p>
     */
    private List<CarbsEntry> withProspectiveMeal(List<CarbsEntry> history,
                                                 PredictRequest req,
                                                 UUID userId,
                                                 LocalDateTime mealTime) {
        double carbsG   = safe(req.getCarbs());
        double proteinG = safe(req.getProtein());
        double fatG     = safe(req.getFat());
        double fiberG   = safe(req.getFiber());

        List<CarbsEntry> entries = new ArrayList<>(history);

        if (carbsG > 0) {
            entries.add(CarbsEntry.builder()
                    .id(UUID.randomUUID())
                    .timestamp(mealTime)
                    .carbs(carbsG).protein(proteinG).fat(fatG).fiber(fiberG)
                    .mealType("predict").userId(userId)
                    .build());
        }

        // Protein converts to glucose via gluconeogenesis (~50 % glucogenic, 2-4 h onset).
        // Fat's effect on gastric emptying is already modelled by MacroNutrientGastricModel
        // via BETA_FAT = 2.20 in computeTMaxG(); adding fat kcal here caused +1.1 mmol/L
        // meal-tail overshoot in the backtest (Variant B).
        double glucFrac = req.getGluconeogenicFraction() != null
                ? req.getGluconeogenicFraction() : 0.50;
        int    pgnOnset = req.getFpuOnsetMin() != null
                ? req.getFpuOnsetMin() : PGN_ONSET_MIN;

        double pgnEquivCarbs = proteinG * glucFrac * PGN_CARB_FACTOR;
        if (pgnEquivCarbs >= FPU_MIN_EQUIV_G) {
            entries.add(CarbsEntry.builder()
                    .id(UUID.randomUUID())
                    .timestamp(mealTime.plusMinutes(pgnOnset))
                    .carbs(pgnEquivCarbs)
                    .mealType("fpu-equiv")
                    .userId(userId)
                    .build());
        }
        return entries;
    }

    // -- Note loaders ---------------------------------------------------------

    private List<Note> loadRecentNotes(UUID userId, LocalDateTime now) {
        try {
            return noteRepository.findByUserIdAndTimestampBetween(userId, now.minusHours(8), now);
        } catch (Exception e) {
            log.warn("loadRecentNotes user={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private List<Note> loadLongActingNotes(UUID userId, LocalDateTime now) {
        try {
            return noteRepository.findByUserIdAndTimestampBetween(userId, now.minusHours(36), now)
                    .stream().filter(Note::isLongActing).toList();
        } catch (Exception e) {
            log.warn("loadLongActingNotes user={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    // -- Note converters -------------------------------------------------------

    private List<CarbsEntry> toCarbsEntries(List<Note> notes) {
        return notes.stream()
                .filter(n -> n.getCarbs() != null && n.getCarbs() > 0)
                .map(noteToCarbsEntryMapper::toCarbsEntry)
                .collect(java.util.stream.Collectors.toList());
    }

    private List<InsulinDose> toInsulinDoses(List<Note> notes, UUID userId) {
        return notes.stream()
                .filter(n -> n.getInsulin() != null && n.getInsulin() > 0 && !n.isLongActing())
                .map(n -> InsulinDose.builder()
                        .id(n.getId())
                        .timestamp(n.getTimestamp())
                        .units(n.getInsulin())
                        .type(InsulinDose.InsulinType.BOLUS)
                        .mealType(n.getMeal())
                        .userId(userId)
                        .build())
                .collect(java.util.stream.Collectors.toList());
    }

    private static double safe(Double v) {
        return v != null && v > 0.0 ? v : 0.0;
    }
}
