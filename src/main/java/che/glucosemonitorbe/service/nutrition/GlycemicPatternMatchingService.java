package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.entity.GlycemicResponsePattern;
import che.glucosemonitorbe.repository.GlycemicResponsePatternRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Matches a NutritionSnapshot against glycemic_response_patterns and enriches the snapshot
 * with bolus strategy, expected absorption duration, and meal sequencing priority.
 *
 * Matching priority (highest specificity wins):
 *   1. Fiber barrier (has_fiber_barrier) — checked first regardless of GI/GL
 *   2. Double Wave trigger (fat≥40g OR protein≥25g)
 *   3. Flat Plateau (protein≥30g, low GL)
 *   4. Fast Spike (GI≥70, GL≥20)
 *   5. Slow Climb (GI≤55)
 *   6. No match → snapshot returned unchanged
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

        for (GlycemicResponsePattern p : patterns) {
            if (matches(p, gi, gl, fat, protein, fiber)) {
                return p;
            }
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
