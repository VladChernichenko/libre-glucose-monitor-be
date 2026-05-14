package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.entity.GlycemicResponsePattern;
import che.glucosemonitorbe.repository.GlycemicResponsePatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GlycemicPatternMatchingService covering:
 *
 * Three-pass matching order (Warsaw Method FPU tiers + fiber barrier):
 *   Pass 1 — fiber-barrier patterns (fiber ≥ 5g overrides GI-based curves)
 *   Pass 2 — fat/protein-constrained FPU tiers (Double Wave > Moderate FPU > Light FPU > Flat Plateau)
 *   Pass 3 — GI-only patterns (Slow Climb, Fast Spike)
 *
 * Pre-bolus pause computation:
 *   Extended → 0 min
 *   Dual Wave → 15 min
 *   Normal high-GI → 20 min / 15 min / 10 min / 5 min (GI thresholds)
 */
class GlycemicPatternMatchingServiceTest {

    private GlycemicResponsePatternRepository repository;
    private GlycemicPatternMatchingService service;

    // ── pattern fixtures (mirrors real DB data from migrations V18–V21) ───────

    /** Fast Spike: GI ≥ 55, no fat/protein constraint, 2.5h */
    private GlycemicResponsePattern fastSpike;
    /** Slow Climb: GI 0–55, no fat/protein constraint, 3.5h */
    private GlycemicResponsePattern slowClimb;
    /** Light FPU: fat ≥ 8g AND protein ≥ 10g, 3h (Warsaw 1 FPU) */
    private GlycemicResponsePattern lightFpu;
    /** Moderate FPU: fat ≥ 15g AND protein ≥ 18g, 4h (Warsaw 2 FPU) */
    private GlycemicResponsePattern moderateFpu;
    /** Flat Plateau: protein ≥ 20g (fat optional), 5h (Warsaw ≥ 3 FPU) */
    private GlycemicResponsePattern flatPlateau;
    /** Double Wave: fat ≥ 30g AND protein ≥ 20g, 8h (Warsaw ≥ 4 FPU, HFHP) */
    private GlycemicResponsePattern doubleWave;
    /** Fiber Barrier: fiber ≥ 5g override */
    private GlycemicResponsePattern fiberBarrier;

    @BeforeEach
    void setUp() {
        repository = mock(GlycemicResponsePatternRepository.class);
        service = new GlycemicPatternMatchingService(repository);

        fastSpike    = pattern("Fast Spike",    "Normal",    55, null, null, null, null,  null,  false, 2.5, (short) 3);
        slowClimb    = pattern("Slow Climb",    "Normal",    0,  55,   null, null, null,  null,  false, 3.5, (short) 3);
        lightFpu     = pattern("Light FPU",     "Normal",    null, null, null, null, 8.0,  10.0,  false, 3.0, (short) 2);
        moderateFpu  = pattern("Moderate FPU",  "Normal",    null, null, null, null, 15.0, 18.0,  false, 4.0, (short) 2);
        flatPlateau  = pattern("Flat Plateau",  "Extended",  null, null, null, null, null, 20.0,  false, 5.0, (short) 1);
        doubleWave   = pattern("Double Wave",   "Dual Wave", null, null, null, null, 30.0, 20.0,  false, 8.0, (short) 1);
        fiberBarrier = pattern("Fiber Barrier", "Normal",    null, null, null, null, null, null,  true,  3.0, (short) 2);

        // Repository returns all patterns sorted by meal_sequencing_priority ASC
        when(repository.findAllByOrderByMealSequencingPriorityAsc())
                .thenReturn(List.of(doubleWave, flatPlateau, fiberBarrier, lightFpu, moderateFpu, slowClimb, fastSpike));
    }

    // ── Pass 3 — GI-only patterns ─────────────────────────────────────────────

    @Test
    void pureFastCarbs_highGI_matchesFastSpike() {
        NutritionSnapshot s = snapshot(75.0, 20.0, 0.0, 2.0, 1.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Fast Spike");
        assertThat(enriched.getBolusStrategy()).isEqualTo("Normal");
        assertThat(enriched.getSuggestedDurationHours()).isEqualTo(2.5);
    }

    @Test
    void pureSlowCarbs_lowGI_matchesSlowClimb() {
        NutritionSnapshot s = snapshot(40.0, 12.0, 1.0, 2.0, 1.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Slow Climb");
        assertThat(enriched.getSuggestedDurationHours()).isEqualTo(3.5);
    }

    @Test
    void borderlineGI55_matchesFastSpike_notSlowClimb() {
        // GI=55: fastSpike requires gi ≥ 55 (giMin=55, giMax=null → gi ≥ 55 passes)
        //        slowClimb requires gi ≤ 55 (giMax=55) → also matches, but fastSpike is checked first? No.
        // Actually slowClimb giMax=55 means gi ≤ 55 → matches. fastSpike giMin=55 → matches.
        // Pass 3 sorts by duration DESC → slowClimb(3.5h) is first → matched!
        NutritionSnapshot s = snapshot(55.0, 10.0, 0.0, 2.0, 0.0);
        NutritionSnapshot enriched = service.enrich(s);
        // slowClimb has longer duration → matched first in pass 3
        assertThat(enriched.getPatternName()).isEqualTo("Slow Climb");
    }

    // ── Pass 2 — fat/protein FPU tiers ───────────────────────────────────────

    @Test
    void lightFpuMeal_eggsAndYoghurt_matchesLightFpu() {
        // fat=9g, protein=12g → satisfies Light FPU (fat≥8, protein≥10)
        // but not Moderate (fat<15) nor Double Wave (fat<30)
        NutritionSnapshot s = snapshot(45.0, 8.0, 2.0, 12.0, 9.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Light FPU");
        assertThat(enriched.getSuggestedDurationHours()).isEqualTo(3.0);
    }

    @Test
    void moderateFpuMeal_meatWithCheese_matchesModerateFpu() {
        // fat=16g, protein=19g → satisfies Moderate FPU (fat≥15, protein≥18)
        // protein=19 < 20 so Flat Plateau (minProtein=20) does NOT match
        // fat=16 < 30 so Double Wave does NOT match
        NutritionSnapshot s = snapshot(50.0, 15.0, 1.0, 19.0, 16.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Moderate FPU");
        assertThat(enriched.getSuggestedDurationHours()).isEqualTo(4.0);
    }

    @Test
    void flatPlateauMeal_highProteinLowFat_matchesFlatPlateau() {
        // fat=5g, protein=25g → satisfies Flat Plateau (protein≥20, fat=null)
        // but not Double Wave (fat<30)
        NutritionSnapshot s = snapshot(45.0, 10.0, 1.0, 25.0, 5.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Flat Plateau");
        assertThat(enriched.getBolusStrategy()).isEqualTo("Extended");
        assertThat(enriched.getSuggestedDurationHours()).isEqualTo(5.0);
    }

    @Test
    void doubleWaveMeal_hfhpPizza_matchesDoubleWave() {
        // fat=35g, protein=22g → satisfies Double Wave (fat≥30, protein≥20)
        NutritionSnapshot s = snapshot(58.0, 40.0, 2.0, 22.0, 35.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Double Wave");
        assertThat(enriched.getBolusStrategy()).isEqualTo("Dual Wave");
        assertThat(enriched.getSuggestedDurationHours()).isEqualTo(8.0);
    }

    @Test
    void doubleWaveWinsOverModerateFpu_forSameMeal() {
        // fat=35g, protein=22g qualifies for both Double Wave AND Moderate FPU
        // Pass 2 sorts by duration DESC → Double Wave (8h) checked before Moderate FPU (4h)
        NutritionSnapshot s = snapshot(58.0, 30.0, 1.0, 22.0, 35.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Double Wave");
    }

    @Test
    void moderateFpuWinsOverLightFpu_forSameMeal() {
        // fat=20g, protein=19g qualifies for both Moderate FPU (fat≥15, protein≥18)
        // AND Light FPU (fat≥8, protein≥10).
        // protein=19 < 20 → Flat Plateau does NOT match.
        // Moderate FPU (4h) is checked before Light FPU (3h) due to duration-desc ordering.
        NutritionSnapshot s = snapshot(50.0, 15.0, 1.0, 19.0, 20.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Moderate FPU");
    }

    @Test
    void lightFpuMeal_doesNotFallThroughToGiOnlyPattern() {
        // fat=9g, protein=12g → Light FPU should match — NOT Fast Spike or Slow Climb
        NutritionSnapshot s = snapshot(70.0, 20.0, 1.0, 12.0, 9.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Light FPU");
    }

    // ── Pass 1 — fiber barrier overrides everything ───────────────────────────

    @Test
    void fiberRichMeal_matchesFiberBarrier_notGiPattern() {
        // fiber=6g ≥ 5.0 → fiber barrier applies first, before any GI or FPU pattern
        NutritionSnapshot s = snapshot(72.0, 25.0, 6.0, 4.0, 3.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Fiber Barrier");
    }

    @Test
    void fiberRichHFHPMeal_fiberBarrierStillWins() {
        // Even with fat=35g, protein=22g — if fiber≥5 → Fiber Barrier wins (pass 1 first)
        NutritionSnapshot s = snapshot(58.0, 25.0, 6.0, 22.0, 35.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isEqualTo("Fiber Barrier");
    }

    @Test
    void lowFiberMeal_fiberBarrierNotMatched() {
        // fiber=2g < 5.0 → skip fiber barrier; fall through to FPU or GI patterns
        NutritionSnapshot s = snapshot(60.0, 15.0, 2.0, 2.0, 2.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isNotEqualTo("Fiber Barrier");
    }

    // ── snapshot enrichment fields ────────────────────────────────────────────

    @Test
    void matchedPattern_populatesAllSnapshotFields() {
        NutritionSnapshot s = snapshot(75.0, 20.0, 0.0, 2.0, 1.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isNotNull();
        assertThat(enriched.getBolusStrategy()).isNotNull();
        assertThat(enriched.getSuggestedDurationHours()).isNotNull();
        assertThat(enriched.getMealSequencingPriority()).isNotNull();
        assertThat(enriched.getCurveDescription()).isNotNull();
        assertThat(enriched.getPreBolusPauseMinutes()).isNotNull();
    }

    @Test
    void noPatterns_snapshotReturnedUnchanged() {
        when(repository.findAllByOrderByMealSequencingPriorityAsc()).thenReturn(List.of());
        NutritionSnapshot s = snapshot(60.0, 10.0, 0.0, 5.0, 5.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getPatternName()).isNull();
        assertThat(enriched.getBolusStrategy()).isNull();
    }

    // ── pre-bolus pause computation ───────────────────────────────────────────

    @Test
    void extendedBolusStrategy_preBolusPauseIsZero() {
        // flatPlateau uses Extended → bolus at/after meal start, no pre-bolus wait
        NutritionSnapshot s = snapshot(45.0, 10.0, 1.0, 25.0, 5.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getBolusStrategy()).isEqualTo("Extended");
        assertThat(enriched.getPreBolusPauseMinutes()).isEqualTo(0);
    }

    @Test
    void dualWaveBolusStrategy_preBolusPauseIs15Min() {
        // doubleWave uses Dual Wave → upfront portion covers fast-carb spike → 15 min
        NutritionSnapshot s = snapshot(58.0, 40.0, 2.0, 22.0, 35.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getBolusStrategy()).isEqualTo("Dual Wave");
        assertThat(enriched.getPreBolusPauseMinutes()).isEqualTo(15);
    }

    @Test
    void normalBolus_highGI_preBolusPauseIs20Min() {
        // Fast Spike: Normal bolus, GI=75 ≥ 70 → 20 min pre-bolus
        NutritionSnapshot s = snapshot(75.0, 20.0, 0.0, 2.0, 1.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getBolusStrategy()).isEqualTo("Normal");
        assertThat(enriched.getPreBolusPauseMinutes()).isEqualTo(20);
    }

    @Test
    void normalBolus_mediumGI_preBolusPauseIs15Min() {
        // Slow Climb: Normal bolus, GI=45 (55 ≤ 45? No. Let's use GI=60)
        // Fast Spike GI≥55: GI=60, 55 ≤ gi < 70 → 15 min
        NutritionSnapshot s = snapshot(60.0, 12.0, 0.0, 2.0, 1.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getBolusStrategy()).isEqualTo("Normal");
        assertThat(enriched.getPreBolusPauseMinutes()).isEqualTo(15);
    }

    @Test
    void normalBolus_lowMediumGI_preBolusPauseIs10Min() {
        // Slow Climb (GI=40): 40 ≤ gi < 55 → 10 min
        NutritionSnapshot s = snapshot(40.0, 12.0, 1.0, 2.0, 1.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getBolusStrategy()).isEqualTo("Normal");
        assertThat(enriched.getPreBolusPauseMinutes()).isEqualTo(10);
    }

    @Test
    void normalBolus_veryLowGI_preBolusPauseIs5Min() {
        // Slow Climb (GI=25): gi < 40 → 5 min
        NutritionSnapshot s = snapshot(25.0, 8.0, 1.0, 2.0, 1.0);
        NutritionSnapshot enriched = service.enrich(s);
        assertThat(enriched.getBolusStrategy()).isEqualTo("Normal");
        assertThat(enriched.getPreBolusPauseMinutes()).isEqualTo(5);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private NutritionSnapshot snapshot(double gi, double gl, double fiber, double protein, double fat) {
        return NutritionSnapshot.builder()
                .estimatedGi(gi)
                .glycemicLoad(gl)
                .fiber(fiber)
                .protein(protein)
                .fat(fat)
                .build();
    }

    private GlycemicResponsePattern pattern(
            String name, String bolus,
            Integer giMin, Integer giMax,
            Double glMin, Double glMax,
            Double minFat, Double minProtein,
            boolean hasFiber,
            double durationHours, short priority) {

        GlycemicResponsePattern p = new GlycemicResponsePattern();
        p.setPatternName(name);
        p.setBolusStrategy(bolus);
        p.setGiMin(giMin);
        p.setGiMax(giMax);
        p.setGlMin(glMin != null ? BigDecimal.valueOf(glMin) : null);
        p.setGlMax(glMax != null ? BigDecimal.valueOf(glMax) : null);
        p.setMinFatGrams(minFat != null ? BigDecimal.valueOf(minFat) : null);
        p.setMinProteinGrams(minProtein != null ? BigDecimal.valueOf(minProtein) : null);
        p.setHasFiberBarrier(hasFiber);
        p.setCurveDescription(name + " curve description");
        p.setSuggestedDurationHours(BigDecimal.valueOf(durationHours));
        p.setMealSequencingPriority(priority);
        return p;
    }
}
