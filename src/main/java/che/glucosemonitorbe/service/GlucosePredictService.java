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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implements the {@code POST /api/predict} prediction pipeline.
 *
 * <h3>Pipeline steps</h3>
 * <ol>
 *   <li>Load 8-hour carb and insulin history from the database.</li>
 *   <li>Build Hovorka parameters for the user; override tMaxG via
 *       {@link MacroNutrientGastricModel} (Elashoff + fiber viscosity).</li>
 *   <li>Add the prospective meal as a future CarbsEntry at t = 0.</li>
 *   <li>Optimise pre-bolus pause: run the ODE for candidate pauses
 *       [0, 5, 10, 15, 20, 25, 30] min and pick the one that minimises
 *       ∫(G(t) − 5.5)² dt over the horizon.</li>
 *   <li>Return the final prediction curve, recommended pause, and bolus strategy.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlucosePredictService {

    private static final int     DEFAULT_HORIZON_MIN = 300;
    private static final double  TARGET_GLUCOSE      = 5.5;   // mmol/L
    private static final int[]   PREBOLUS_CANDIDATES = {0, 5, 10, 15, 20, 25, 30};

    private final HovorkaGlucosePredictionService hovorkaService;
    private final HovorkaParameterService         paramService;
    private final UserService                     userService;
    private final NoteRepository                  noteRepository;

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

        // ── 1. Load history ───────────────────────────────────────────────────
        List<Note> recentNotes     = loadRecentNotes(userId, now);
        List<Note> longActingNotes = loadLongActingNotes(userId, now);

        List<CarbsEntry>  pastCarbs = toCarbsEntries(recentNotes, userId);
        List<InsulinDose> pastDoses = toInsulinDoses(recentNotes, userId);

        // ── 2. Build macro-modulated Hovorka params ───────────────────────────
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
                baseParams.vG(), baseParams.f01(), baseParams.egpNet(),
                baseParams.k12(), baseParams.k21(),
                tMaxGMod, baseParams.aG(), baseParams.isf(), baseParams.weightKg());

        int horizon = req.getHorizonMinutes() != null
                ? Math.max(60, Math.min(480, req.getHorizonMinutes()))
                : DEFAULT_HORIZON_MIN;

        // ── 3. Add prospective meal at t = 0 ─────────────────────────────────
        List<CarbsEntry> carbsWithMeal = new ArrayList<>(pastCarbs);
        if (carbsG > 0) {
            carbsWithMeal.add(CarbsEntry.builder()
                    .id(UUID.randomUUID())
                    .timestamp(now)
                    .carbs(carbsG).protein(proteinG).fat(fatG).fiber(fiberG)
                    .mealType("predict").userId(userId)
                    .build());
        }

        // ── 4. Optimise pre-bolus pause ───────────────────────────────────────
        double insulinDose = safe(req.getInsulinDose());
        int bestPause = optimisePreBolus(
                req.getCurrentGlucose(), now,
                carbsWithMeal, pastDoses, longActingNotes,
                userId, mealParams, insulinDose, horizon);

        // ── 5. Final simulation with optimal bolus timing ─────────────────────
        List<InsulinDose> finalDoses = new ArrayList<>(pastDoses);
        if (insulinDose > 0) {
            finalDoses.add(InsulinDose.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .units(insulinDose)
                    .type(InsulinDose.InsulinType.BOLUS)
                    .timestamp(now.plusMinutes(bestPause))
                    .build());
        }

        List<PredictionPointDTO> curve = hovorkaService.buildPredictionPath(
                mealParams,
                req.getCurrentGlucose(), now,
                carbsWithMeal, finalDoses, longActingNotes,
                userId, horizon);

        double betaWeighted = MacroNutrientGastricModel.weightedBeta(carbsG, proteinG, fatG);
        String strategy     = MacroNutrientGastricModel.bolusStrategy(fatG, proteinG);

        log.debug("predict user={} tMaxG={:.1f}min β={:.2f} pause={}min strategy={}",
                userId, tMaxGMod, betaWeighted, bestPause, strategy);

        return PredictResponse.builder()
                .curve(curve)
                .preBolusMinutes(bestPause)
                .bolusStrategy(strategy)
                .tMaxGUsed(Math.round(tMaxGMod * 10.0) / 10.0)
                .betaWeighted(Math.round(betaWeighted * 100.0) / 100.0)
                .build();
    }

    // ── Pre-bolus optimisation ────────────────────────────────────────────────

    /**
     * Finds the pre-bolus pause [min] from {@link #PREBOLUS_CANDIDATES} that minimises
     * the integral of squared deviation from 5.5 mmol/L over the prediction horizon.
     *
     * <p>Runs at most {@code PREBOLUS_CANDIDATES.length} ODE simulations — each is
     * a few milliseconds, so the total overhead is negligible.</p>
     */
    private int optimisePreBolus(
            double currentGlucose,
            LocalDateTime now,
            List<CarbsEntry> carbsEntries,
            List<InsulinDose> baseDoses,
            List<Note> longActingNotes,
            UUID userId,
            HovorkaParameters params,
            double insulinDose,
            int horizon) {

        if (insulinDose <= 0) return 0;

        int    bestPause = 0;
        double bestCost  = Double.MAX_VALUE;

        for (int pause : PREBOLUS_CANDIDATES) {
            List<InsulinDose> doses = new ArrayList<>(baseDoses);
            doses.add(InsulinDose.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .units(insulinDose)
                    .type(InsulinDose.InsulinType.BOLUS)
                    .timestamp(now.plusMinutes(pause))
                    .build());

            List<PredictionPointDTO> sim = hovorkaService.buildPredictionPath(
                    params, currentGlucose, now,
                    carbsEntries, doses, longActingNotes, userId, horizon);

            double cost = sim.stream().mapToDouble(pt -> {
                double g   = pt.getPredictedGlucose() != null ? pt.getPredictedGlucose() : currentGlucose;
                double err = g - TARGET_GLUCOSE;
                return err * err;
            }).sum();

            if (cost < bestCost) {
                bestCost  = cost;
                bestPause = pause;
            }
        }
        return bestPause;
    }

    // ── Note loaders ─────────────────────────────────────────────────────────

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

    // ── Note converters ───────────────────────────────────────────────────────

    private List<CarbsEntry> toCarbsEntries(List<Note> notes, UUID userId) {
        return notes.stream()
                .filter(n -> n.getCarbs() != null && n.getCarbs() > 0)
                .map(n -> CarbsEntry.builder()
                        .id(n.getId())
                        .timestamp(n.getTimestamp())
                        .carbs(n.getCarbs())
                        .mealType(n.getMeal())
                        .originalCarbs(n.getCarbs())
                        .userId(userId)
                        .build())
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
