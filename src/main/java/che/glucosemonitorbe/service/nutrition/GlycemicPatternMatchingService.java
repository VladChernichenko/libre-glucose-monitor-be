package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.entity.GlycemicResponsePattern;
import che.glucosemonitorbe.repository.GlycemicResponsePatternRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Matches a NutritionSnapshot against glycemic_response_patterns and enriches the snapshot
 * with bolus strategy, expected absorption duration, and meal sequencing priority.
 *
 * Matching uses two-pass specificity ordering to avoid premature GI-only matches:
 *   Pass 1 — fat/protein-constrained patterns (sorted by duration DESC):
 *     Double Wave (8h, fat+protein) → Flat Plateau (5h, protein) →
 *     Moderate FPU (4h) → Light FPU (3h)
 *   Pass 2 — GI-only patterns (sorted by duration DESC):
 *     Slow Climb (3.5h) → Fast Spike (2.5h)
 *   Fiber barrier patterns (has_fiber_barrier=true) are always checked in Pass 1
 *   before any GI pattern because they override the expected curve shape.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlycemicPatternMatchingService {

    private static final double FIBER_BARRIER_THRESHOLD = 5.0;

    private final GlycemicResponsePatternRepository patternRepository;

    public NutritionSnapshot enrich(NutritionSnapshot snapshot) {
        List<GlycemicResponsePattern> patterns = patternRepository.findAllByOrderByMealSequencingPriorityAsc();
        if (patterns.isEmpty()) {
            return snapshot;
        }

        GlycemicResponsePattern match = findBestMatch(snapshot, patterns);
        if (match == null) {
            log.debug("[GlycemicPattern] no pattern matched for gi={} gl={} fat={} protein={} fiber={}",
                    snapshot.getEstimatedGi(), snapshot.getGlycemicLoad(),
                    snapshot.getFat(), snapshot.getProtein(), snapshot.getFiber());
            return snapshot;
        }

        log.info("[GlycemicPattern] matched '{}' → bolus={} duration={}h seqPriority={}",
                match.getPatternName(), match.getBolusStrategy(),
                match.getSuggestedDurationHours(), match.getMealSequencingPriority());

        snapshot.setPatternName(match.getPatternName());
        snapshot.setBolusStrategy(match.getBolusStrategy());
        snapshot.setSuggestedDurationHours(match.getSuggestedDurationHours().doubleValue());
        snapshot.setMealSequencingPriority((int) match.getMealSequencingPriority());
        snapshot.setCurveDescription(match.getCurveDescription());
        snapshot.setPreBolusPauseMinutes(computePreBolusPause(match.getBolusStrategy(), snapshot));
        return snapshot;
    }

    private GlycemicResponsePattern findBestMatch(NutritionSnapshot s, List<GlycemicResponsePattern> patterns) {
        double fiber   = s.getFiber()   != null ? s.getFiber()   : 0.0;
        double fat     = s.getFat()     != null ? s.getFat()     : 0.0;
        double protein = s.getProtein() != null ? s.getProtein() : 0.0;
        double gi      = s.getEstimatedGi()    != null ? s.getEstimatedGi()    : 0.0;
        double gl      = s.getGlycemicLoad()   != null ? s.getGlycemicLoad()   : 0.0;

        // Comparator: longer suggestedDuration first (more specific FPU tier wins).
        Comparator<GlycemicResponsePattern> byDurationDesc =
                Comparator.comparingDouble((GlycemicResponsePattern p) ->
                        p.getSuggestedDurationHours().doubleValue()).reversed();

        // Pass 1: fiber-barrier patterns (always override GI-based curves when fiber > threshold).
        for (GlycemicResponsePattern p : patterns.stream()
                .filter(GlycemicResponsePattern::isHasFiberBarrier)
                .sorted(byDurationDesc).toList()) {
            if (matches(p, gi, gl, fat, protein, fiber)) return p;
        }

        // Pass 2: fat/protein-constrained patterns — sorted longest→shortest so Double Wave (8h)
        // beats Moderate FPU (4h) beats Light FPU (3h) for the same meal.
        for (GlycemicResponsePattern p : patterns.stream()
                .filter(pat -> !pat.isHasFiberBarrier()
                        && (pat.getMinFatGrams() != null || pat.getMinProteinGrams() != null))
                .sorted(byDurationDesc).toList()) {
            if (matches(p, gi, gl, fat, protein, fiber)) return p;
        }

        // Pass 3: GI-only patterns (no fat/protein constraints) — Fast Spike, Slow Climb.
        for (GlycemicResponsePattern p : patterns.stream()
                .filter(pat -> !pat.isHasFiberBarrier()
                        && pat.getMinFatGrams() == null && pat.getMinProteinGrams() == null)
                .sorted(byDurationDesc).toList()) {
            if (matches(p, gi, gl, fat, protein, fiber)) return p;
        }

        return null;
    }

    /**
     * Recommended pre-bolus pause (minutes to wait after injection before eating).
     *
     * Dual Wave: HFHP meal still contains a fast-carb component (e.g. pizza dough).
     *   The upfront bolus portion (30–50%) must cover that spike, so inject 10–15 min
     *   before eating. Source: ADA/ISPAD Dual-Wave guidelines, Warsaw Method.
     *
     * Extended: pure protein/fat dominant meal with negligible fast-carb peak (e.g. steak).
     *   Bolus starts at or after meal → 0 min pre-bolus.
     *
     * Normal: GI-driven timing — high-GI food absorbs fastest and needs longest head-start.
     */
    private int computePreBolusPause(String bolusStrategy, NutritionSnapshot s) {
        if (bolusStrategy == null) return 15;
        return switch (bolusStrategy) {
            case "Extended" -> 0;            // pure slow absorption — bolus at/after meal start
            case "Dual Wave" -> 15;          // upfront portion covers fast-carb spike in mixed HFHP meal
            default -> {
                double gi = s.getEstimatedGi() != null ? s.getEstimatedGi() : 55.0;
                if (gi >= 70) yield 20;      // fast carbs — inject 20 min early
                if (gi >= 55) yield 15;      // medium GI
                if (gi >= 40) yield 10;      // low-medium GI
                yield 5;                     // very low GI / high-fiber — minimal pause
            }
        };
    }

    private boolean matches(GlycemicResponsePattern p,
                            double gi, double gl, double fat, double protein, double fiber) {
        // Fiber barrier check
        if (p.isHasFiberBarrier() && fiber < FIBER_BARRIER_THRESHOLD) return false;
        if (!p.isHasFiberBarrier() && fiber >= FIBER_BARRIER_THRESHOLD) return false;

        // GI range
        if (p.getGiMin() != null && gi < p.getGiMin()) return false;
        if (p.getGiMax() != null && gi > p.getGiMax()) return false;

        // GL range
        if (p.getGlMin() != null && gl < p.getGlMin().doubleValue()) return false;
        if (p.getGlMax() != null && gl > p.getGlMax().doubleValue()) return false;

        // Fat / protein minimums
        if (p.getMinFatGrams() != null && fat < p.getMinFatGrams().doubleValue()) return false;
        if (p.getMinProteinGrams() != null && protein < p.getMinProteinGrams().doubleValue()) return false;

        return true;
    }
}
