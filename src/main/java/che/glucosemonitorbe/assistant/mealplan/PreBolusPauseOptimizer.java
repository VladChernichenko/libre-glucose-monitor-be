package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.ProspectiveNoteDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import che.glucosemonitorbe.service.prebolus.PreBolusContext;
import che.glucosemonitorbe.service.prebolus.PreBolusResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The single pre-bolus pause engine (spec §6.4 step 3), on the dashboard forecast: each candidate
 * pause is one {@link GlucoseCalculationsService#calculateGlucoseData} call with the dose taken now
 * and the meal at now + pause (negative {@code minutesAgo}), so the pause, the meal card's curve and
 * the dashboard come from one computation.
 *
 * <ul>
 *   <li>A pre-bolus already in flight ({@link PreBolusResolver}): no new pause; the forecast has the
 *       meal now and no extra dose (the logged one is already in the notes).</li>
 *   <li>Otherwise every candidate in {@link #CANDIDATES} is scored by {@link #timeWeightedMeanCost};
 *       a candidate whose forecast minimum is under {@link #MIN_FLOOR} is rejected; ties go to the
 *       shorter pause. If all are rejected: no pause (eat now), and the reason says why.</li>
 * </ul>
 * The cost moved here unchanged from the deleted {@code GlucosePredictService}.
 */
@Service
public class PreBolusPauseOptimizer {

    static final int[] CANDIDATES = {0, 5, 10, 15, 20, 25, 30};
    /** No candidate may forecast below this [mmol/L]. */
    static final double MIN_FLOOR = 4.5;

    static final double TARGET_GLUCOSE = 5.5;
    /** Predicted glucose below this [mmol/L] is treated as a hypo and penalised hard. */
    static final double HYPO_THRESHOLD = 3.9;
    /** Extra quadratic penalty weight applied per (3.9 − G)² below the hypo threshold. */
    static final double HYPO_PENALTY_WEIGHT = 50.0;
    /** Mild multiplier for points between the hypo threshold and target (low side of 5.5). */
    static final double LOW_SIDE_WEIGHT = 2.0;
    /** Every candidate is judged over this many minutes after its own meal (as the old engine did). */
    static final int POST_MEAL_MINUTES = 240;
    /** Path asked of the dashboard forecast: long enough for the longest pause plus its window. */
    static final int PATH_MINUTES = POST_MEAL_MINUTES + 30;
    /** Notes searched for a pre-bolus in flight; the resolver itself caps its age at 2 h. */
    private static final int LOOKBACK_HOURS = 8;

    private final GlucoseCalculationsService calculations;
    private final PreBolusResolver preBolusResolver;
    private final NoteRepository notes;
    private final UserService users;
    private final ObjectMapper objectMapper;

    public PreBolusPauseOptimizer(GlucoseCalculationsService calculations, PreBolusResolver preBolusResolver,
                                  NoteRepository notes, UserService users, ObjectMapper objectMapper) {
        this.calculations = calculations;
        this.preBolusResolver = preBolusResolver;
        this.notes = notes;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    /**
     * @param now            the user's local wall time (as the dashboard's ClientTimeInfo)
     * @param currentGlucose [mmol/L]
     * @param doseUnits      the meal bolus [U], taken now; 0 or less = no dose, only pause 0 is scored
     * @param meal           carbs 0 or less = nothing to time: no pause, and the curve is the dose alone
     */
    public PauseChoice choose(UUID userId, LocalDateTime now, double currentGlucose, double doseUnits,
                              MealMacros meal) {
        String username = users.getUserById(userId).getUsername();
        String nutritionJson = nutritionJson(meal);
        Double dose = doseUnits > 0 ? doseUnits : null;

        if (meal.carbs() <= 0) {
            // The forecast drops a prospective meal without carbs, so every candidate would be
            // "insulin, no meal": there is no pause to choose.
            List<PredictionPointDTO> curve = forecast(username, now, currentGlucose, dose, null, null, 0);
            return choice(null, curve, "No carbs, so no pre-bolus pause.");
        }

        List<Note> recent = notes.findByUserIdAndTimestampBetween(userId, now.minusHours(LOOKBACK_HOURS), now);
        Optional<PreBolusContext> inFlight = preBolusResolver.resolve(null, recent, now);
        if (inFlight.isPresent()) {
            PreBolusContext ctx = inFlight.get();
            List<PredictionPointDTO> curve = forecast(username, now, currentGlucose, null, meal, nutritionJson, 0);
            return choice(null, curve, String.format(Locale.ROOT,
                    "A pre-bolus of %.1f U is already in flight (%d min ago), so no new pause; the forecast has the meal now.",
                    ctx.units(), ctx.elapsedMinutes()));
        }

        List<PredictionPointDTO> eatNow = null;
        Integer bestPause = null;
        List<PredictionPointDTO> bestCurve = null;
        double bestCost = Double.MAX_VALUE;
        double lowestMin = Double.MAX_VALUE;
        for (int pause : CANDIDATES) {
            if (dose == null && pause > 0) break;
            List<PredictionPointDTO> curve = forecast(username, now, currentGlucose, dose, meal, nutritionJson, pause);
            if (pause == 0) eatNow = curve;
            double min = min(curve);
            if (!Double.isNaN(min)) lowestMin = Math.min(lowestMin, min);
            if (Double.isNaN(min) || min < MIN_FLOOR) continue; // an empty forecast never wins
            double cost = timeWeightedMeanCost(curve, currentGlucose);
            if (cost < bestCost) { // strict: a tie keeps the shorter pause
                bestCost = cost;
                bestPause = pause;
                bestCurve = curve;
            }
        }

        if (bestPause == null) {
            String lowest = lowestMin == Double.MAX_VALUE ? ""
                    : String.format(Locale.ROOT, " (lowest %.1f)", lowestMin);
            String reason = dose == null
                    ? String.format(Locale.ROOT, "Eating now forecasts a low under %.1f mmol/L%s.", MIN_FLOOR, lowest)
                    : String.format(Locale.ROOT,
                            "Every pause from 0 to 30 min forecasts a low under %.1f mmol/L%s, so no pre-bolus pause: eat now.",
                            MIN_FLOOR, lowest);
            return choice(null, eatNow, reason);
        }
        PauseChoice chosen = choice(bestPause, bestCurve, null);
        String reason = dose == null
                ? "No dose, so no pause."
                : String.format(Locale.ROOT,
                        "A %d-min pause keeps the forecast closest to %.1f mmol/L, between %.1f and %.1f.",
                        bestPause, TARGET_GLUCOSE, chosen.min(), chosen.peak());
        return new PauseChoice(chosen.pauseMinutes(), chosen.curve(), chosen.peak(), chosen.min(), reason);
    }

    // -- forecast ----------------------------------------------------------------------------

    /**
     * The forecast for one given dose and pause, with no pause search and no in-flight lookup: the
     * dose now (none if 0 or less), the meal at now + {@code pauseMinutes} (none without carbs), cropped
     * to now + pause + {@link #POST_MEAL_MINUTES} exactly as each candidate in {@link #choose} is.
     * Used for the history banner's second curve and the low re-check of a chosen dose.
     */
    public List<PredictionPointDTO> forecastDose(UUID userId, LocalDateTime now, double currentGlucose,
                                                 double doseUnits, MealMacros meal, int pauseMinutes) {
        String username = users.getUserById(userId).getUsername();
        Double dose = doseUnits > 0 ? doseUnits : null;
        if (meal == null || meal.carbs() <= 0) {
            return forecast(username, now, currentGlucose, dose, null, null, 0);
        }
        return forecast(username, now, currentGlucose, dose, meal, nutritionJson(meal), pauseMinutes);
    }

    /**
     * The dashboard forecast with {@code dose} now (none if null) and the meal (none if null) at
     * now + pause, asked for {@link #PATH_MINUTES} and cropped to now + pause + {@link #POST_MEAL_MINUTES},
     * so each candidate is scored and floor-checked over the same 240 min after its own meal.
     */
    private List<PredictionPointDTO> forecast(String username, LocalDateTime now, double currentGlucose,
                                              Double dose, MealMacros meal, String nutritionJson, int pause) {
        List<ProspectiveNoteDTO> prospective = new ArrayList<>();
        if (dose != null) {
            prospective.add(ProspectiveNoteDTO.builder().insulin(dose).minutesAgo(0).build());
        }
        if (meal != null) {
            prospective.add(ProspectiveNoteDTO.builder()
                    .carbs(meal.carbs())
                    .nutritionProfileJson(nutritionJson)
                    .minutesAgo(-pause)
                    .build());
        }
        // Carrying prospective notes, this never computes carb advice.
        List<PredictionPointDTO> path = calculations.calculateGlucoseData(GlucoseCalculationsRequest.builder()
                .userId(username) // resolved by username
                .currentGlucose(currentGlucose)
                // timestamp only: no timezone, so this internal run never rewrites the user's zone
                .clientTimeInfo(ClientTimeInfo.builder().timestamp(now.toString()).build())
                .prospectiveNotes(prospective)
                .predictionPathMinutes(PATH_MINUTES)
                .build()).getPredictionPath();
        if (path == null) return List.of();
        LocalDateTime until = now.plusMinutes(pause + POST_MEAL_MINUTES);
        return path.stream()
                .filter(p -> p.getTimestamp() == null || !p.getTimestamp().isAfter(until))
                .toList();
    }

    private String nutritionJson(MealMacros meal) {
        try {
            return objectMapper.writeValueAsString(NutritionSnapshot.builder()
                    .totalCarbs(meal.carbs())
                    .fat(meal.fat())
                    .protein(meal.protein())
                    .fiber(meal.fiber())
                    .estimatedGi(meal.gi())
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise meal macros", e);
        }
    }

    private static PauseChoice choice(Integer pause, List<PredictionPointDTO> curve, String reason) {
        return new PauseChoice(pause, curve, peak(curve), min(curve), reason);
    }

    /** Lowest predicted point; NaN for an empty curve. */
    static double min(List<PredictionPointDTO> curve) {
        return curve.stream().map(PredictionPointDTO::getPredictedGlucose).filter(g -> g != null)
                .mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
    }

    /** Highest predicted point; NaN for an empty curve. */
    static double peak(List<PredictionPointDTO> curve) {
        return curve.stream().map(PredictionPointDTO::getPredictedGlucose).filter(g -> g != null)
                .mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    // -- cost (moved from GlucosePredictService) ----------------------------------------------

    /**
     * Trapezoidal time-weighted mean of {@link #pointPenalty} over a candidate's curve:
     * {@code Σ ½(cᵢ + cᵢ₊₁)·Δtᵢ / (t_last − t_first)}. Time-weighted because the ODE grid is not
     * uniform (5 min below 4 h, 10 min beyond), so a per-sample mean would weigh the sparse tail at
     * half its duration. Every point from the injection onward counts, including the pre-meal
     * interval where an over-long pre-bolus does its damage. A window with fewer than two usable
     * points, or no time span, scores {@link Double#MAX_VALUE}: a degenerate window must never win.
     */
    static double timeWeightedMeanCost(List<PredictionPointDTO> sim, double fallbackGlucose) {
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
        double spanMin = minutesBetween(pts.get(0).getTimestamp(), pts.get(pts.size() - 1).getTimestamp());
        if (spanMin <= 0) {
            return Double.MAX_VALUE;
        }
        double integral = 0.0;
        double prevCost = pointPenalty(pts.get(0), fallbackGlucose);
        for (int i = 1; i < pts.size(); i++) {
            double cost = pointPenalty(pts.get(i), fallbackGlucose);
            double dt = minutesBetween(pts.get(i - 1).getTimestamp(), pts.get(i).getTimestamp());
            integral += 0.5 * (prevCost + cost) * dt;
            prevCost = cost;
        }
        return integral / spanMin;
    }

    /**
     * Squared deviation from {@link #TARGET_GLUCOSE}, asymmetric: ×{@link #LOW_SIDE_WEIGHT} between
     * the hypo threshold and target, plus {@link #HYPO_PENALTY_WEIGHT}·(3.9 − G)² below 3.9.
     */
    static double pointPenalty(PredictionPointDTO pt, double fallbackGlucose) {
        double g = pt.getPredictedGlucose() != null ? pt.getPredictedGlucose() : fallbackGlucose;
        double err = g - TARGET_GLUCOSE;
        double base = err * err;
        if (g < HYPO_THRESHOLD) {
            double below = HYPO_THRESHOLD - g;
            base += HYPO_PENALTY_WEIGHT * below * below;
        } else if (g < TARGET_GLUCOSE) {
            base *= LOW_SIDE_WEIGHT;
        }
        return base;
    }

    private static double minutesBetween(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toNanos() / 60_000_000_000.0;
    }
}
