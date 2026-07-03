package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.MacroNutrientGastricModel;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Replays the Hovorka predictor over a user's own history to produce {@link AnchorSample}s for
 * calibration. This is the main-source, DB-driven counterpart of the offline
 * {@code BacktestHarness}: open-loop, at each anchor {@code t0} it feeds the predictor the inputs it
 * would have had plus the meals/boluses that actually occurred over the horizon, then compares the
 * predicted curve to the real CGM trace.
 *
 * <h3>Cost control</h3>
 * <p>The optimiser evaluates {@link #replay(TwinScales)} many times, so the per-anchor input
 * assembly (history windows, macro-modulated {@code tMaxG}, regime) is done <b>once</b> in the
 * constructor; each replay only re-runs the ODE with freshly scaled parameters. Anchors are strided
 * and capped ({@link Config#maxAnchors}) to keep a full calibration well under a second.</p>
 */
public final class PredictionReplayEngine implements AnchorSampleSource {

    // ── FPU constants (mirror GlucosePredictService / BacktestHarness) ──────────
    private static final int    FPU_ONSET_MIN     = 90;
    private static final double FPU_CARB_EQUIV_G  = 10.0;
    private static final double FPU_MIN_EQUIV_G   = 2.0;
    private static final double FPU_GLUC_FRACTION = 0.50;

    /** CGM reading in the replay window. */
    public record Reading(long epochMs, double mmol) {}

    /** A logged event (meal and/or bolus, or a long-acting basal note). */
    public record Event(long epochMs, double carbs, double insulin, boolean longActing,
                        double protein, double fat, double fiber) {}

    /** Replay configuration. */
    public static final class Config {
        /** Prediction horizon per anchor [min]. Shorter than the live 4–8 h path keeps the fit fast
         *  and focused on the well-identified early curve. */
        public int    horizonMin      = 120;
        /** Minutes between anchors. Coarser than the live 5-min cadence — anchors are highly
         *  correlated, so a dense stride adds cost without information. */
        public int    strideMin       = 30;
        /** CGM alignment tolerance when matching an anchor or horizon to a reading [ms]. */
        public long   alignToleranceMs = 300_000; // ±5 min
        /** Hard cap on anchors; if the window yields more they are evenly subsampled. */
        public int    maxAnchors      = 250;
        /** Apply per-meal macro-modulated tMaxG (mirrors GlucosePredictService). */
        public boolean macroTMaxG     = true;
        /** Add FPU-equivalent slow-carb entries from protein/fat (mirrors GlucosePredictService). */
        public boolean fpuEquiv       = true;
        /** Horizons (min) to emit samples at — must be a subset of the model's emission schedule. */
        public int[]  sampleHorizons  = {30, 60, 90, 120};
    }

    private final HovorkaGlucosePredictionService predictor;
    private final HovorkaParameters baseParams;
    private final RapidInsulinIobParameters rapidIob;
    private final UserSettingsDTO settings;
    private final UUID userId;
    private final Config cfg;

    private final long[] cgmT;
    private final double[] cgmG;
    private final List<AnchorContext> anchors;

    /** Per-anchor inputs, assembled once (scale-independent). */
    private record AnchorContext(
            double g0, LocalDateTime now,
            List<CarbsEntry> carbs, List<InsulinDose> insulin, List<Note> longActing,
            Regime regime, HovorkaParameters macroParams) {}

    /**
     * @param predicted  a predictor wired with {@link PredictionResidualProvider#NONE} (raw model)
     * @param baseParams the user's base parameters <b>without</b> any twin overlay
     * @param rapidIob   the user's rapid-insulin IOB parameters (fetched once, reused per anchor)
     * @param settings   the user's settings snapshot (fetched once, reused per anchor)
     * @param userId     user id (passed through to IOB/ISF-window resolution)
     * @param cgm        CGM readings across the window (any order)
     * @param events     logged events across the window (any order)
     */
    public PredictionReplayEngine(HovorkaGlucosePredictionService predicted, HovorkaParameters baseParams,
                                  RapidInsulinIobParameters rapidIob, UserSettingsDTO settings,
                                  UUID userId, List<Reading> cgm, List<Event> events, Config cfg) {
        this.predictor = predicted;
        this.baseParams = baseParams;
        this.rapidIob = rapidIob;
        this.settings = settings;
        this.userId = userId;
        this.cfg = cfg;

        List<Reading> sorted = new ArrayList<>(cgm);
        sorted.sort((a, b) -> Long.compare(a.epochMs(), b.epochMs()));
        this.cgmT = sorted.stream().mapToLong(Reading::epochMs).toArray();
        this.cgmG = sorted.stream().mapToDouble(Reading::mmol).toArray();

        List<Event> sortedEvents = new ArrayList<>(events);
        sortedEvents.sort((a, b) -> Long.compare(a.epochMs(), b.epochMs()));
        this.anchors = prepareAnchors(sortedEvents);
    }

    /** Number of usable anchors found in the window. */
    public int anchorCount() {
        return anchors.size();
    }

    /**
     * Run the model at every prepared anchor with the given scales and collect all
     * (predicted, actual) samples.
     */
    public List<AnchorSample> replay(TwinScales scales) {
        TwinScales s = scales.clamped();
        List<AnchorSample> out = new ArrayList<>();
        for (AnchorContext a : anchors) {
            HovorkaParameters p = applyScales(a.macroParams(), s);
            List<PredictionPointDTO> curve = predictor.buildPredictionPath(
                    p, rapidIob, settings, a.g0(), a.now(), a.carbs(), a.insulin(), a.longActing(),
                    userId, cfg.horizonMin);
            long t0 = a.now().toInstant(ZoneOffset.UTC).toEpochMilli();
            for (PredictionPointDTO pt : curve) {
                int h = (int) Duration.between(a.now(), pt.getTimestamp()).toMinutes();
                if (!isSampleHorizon(h)) continue;
                Double actual = nearest(cgmT, cgmG, t0 + h * 60_000L, cfg.alignToleranceMs);
                if (actual == null) continue;
                out.add(new AnchorSample(h, pt.getPredictedGlucose(), actual, a.g0(),
                        a.regime(), pt.getTimestamp().getHour()));
            }
        }
        return out;
    }

    // ── Anchor preparation ──────────────────────────────────────────────────────

    private List<AnchorContext> prepareAnchors(List<Event> events) {
        List<AnchorContext> result = new ArrayList<>();
        if (cgmT.length == 0) return result;

        long first = cgmT[0];
        long last  = cgmT[cgmT.length - 1];
        long strideMs = cfg.strideMin * 60_000L;
        long horizonMs = cfg.horizonMin * 60_000L;

        // Collect candidate anchor times first so we can evenly subsample to the cap.
        List<Long> candidates = new ArrayList<>();
        for (long t0 = first; t0 + horizonMs <= last; t0 += strideMs) candidates.add(t0);
        int step = Math.max(1, (int) Math.ceil(candidates.size() / (double) cfg.maxAnchors));

        for (int idx = 0; idx < candidates.size(); idx += step) {
            long t0 = candidates.get(idx);
            Double g0 = nearest(cgmT, cgmG, t0, cfg.alignToleranceMs);
            if (g0 == null) continue;
            LocalDateTime now = toLdt(t0);

            List<CarbsEntry> carbs = new ArrayList<>();
            List<InsulinDose> insulin = new ArrayList<>();
            List<Note> longActing = new ArrayList<>();
            boolean carbActive = false, insulinActive = false;
            double domCarbAmt = -1, domCarbs = 0, domProtein = 0, domFat = 0, domFiber = 0;

            for (Event n : events) {
                long age = t0 - n.epochMs();
                boolean inPast   = age >= 0 && age <= 8 * 3600_000L;
                boolean inFuture = age < 0 && -age <= horizonMs;
                if (n.longActing()) {
                    if (age >= 0 && age <= 36 * 3600_000L) longActing.add(makeLongActing(n));
                    continue;
                }
                if (!inPast && !inFuture) continue;

                if (n.carbs() > 0) {
                    carbs.add(CarbsEntry.builder().timestamp(toLdt(n.epochMs())).carbs(n.carbs()).build());
                }
                if (n.insulin() > 0) {
                    insulin.add(InsulinDose.builder().timestamp(toLdt(n.epochMs()))
                            .units(n.insulin()).type(InsulinDose.InsulinType.BOLUS).build());
                }
                if (cfg.fpuEquiv) {
                    double fpu = (n.protein() * 4.0 * FPU_GLUC_FRACTION + n.fat() * 9.0)
                            / 100.0 * FPU_CARB_EQUIV_G;
                    if (fpu >= FPU_MIN_EQUIV_G) {
                        carbs.add(CarbsEntry.builder()
                                .timestamp(toLdt(n.epochMs() + FPU_ONSET_MIN * 60_000L)).carbs(fpu).build());
                    }
                }

                boolean active = age <= 3 * 3600_000L && -age <= horizonMs;
                if (active) {
                    if (n.carbs() > 0 || n.protein() > 0 || n.fat() > 0) carbActive = true;
                    if (n.insulin() > 0) insulinActive = true;
                    if (n.carbs() > domCarbAmt) {
                        domCarbAmt = n.carbs();
                        domCarbs = n.carbs(); domProtein = n.protein(); domFat = n.fat(); domFiber = n.fiber();
                    }
                }
            }

            Regime regime = carbActive ? Regime.MEAL : (insulinActive ? Regime.CORRECTION : Regime.FASTING);

            HovorkaParameters macroParams = baseParams;
            if (cfg.macroTMaxG && (domCarbs + domProtein + domFat) > 0) {
                double tMaxG = MacroNutrientGastricModel.computeTMaxG(
                        domCarbs, domProtein, domFat, domFiber, HovorkaParameterService.HALF_LIFE_TO_TMAX_G);
                macroParams = withTMaxG(baseParams, tMaxG);
            }
            result.add(new AnchorContext(g0, now, carbs, insulin, longActing, regime, macroParams));
        }
        return result;
    }

    // ── Parameter helpers ───────────────────────────────────────────────────────

    /** Apply the v1-active scales (ISF, A_G) to a parameter set. Reserved scales are not wired. */
    private static HovorkaParameters applyScales(HovorkaParameters p, TwinScales s) {
        return new HovorkaParameters(
                p.vG(), p.f01(), p.egpNet(), p.k12(), p.k21(),
                p.tMaxG(), p.aG() * s.agScale(), p.isf() * s.isfScale(), p.weightKg());
    }

    private static HovorkaParameters withTMaxG(HovorkaParameters p, double tMaxG) {
        return new HovorkaParameters(
                p.vG(), p.f01(), p.egpNet(), p.k12(), p.k21(),
                tMaxG, p.aG(), p.isf(), p.weightKg());
    }

    private boolean isSampleHorizon(int h) {
        for (int sh : cfg.sampleHorizons) if (sh == h) return true;
        return false;
    }

    private static Note makeLongActing(Event n) {
        Note note = new Note();
        note.setTimestamp(toLdt(n.epochMs()));
        note.setInsulin(n.insulin() > 0 ? n.insulin() : 10.0);
        note.setType(Note.TYPE_LONG_ACTING);
        return note;
    }

    private static LocalDateTime toLdt(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    /** Nearest CGM value to {@code target} within tolerance, or null. Binary search on sorted times. */
    static Double nearest(long[] t, double[] g, long target, long tolMs) {
        if (t.length == 0) return null;
        int lo = 0, hi = t.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (t[mid] < target) lo = mid + 1; else hi = mid;
        }
        int best = lo;
        if (lo > 0 && Math.abs(t[lo - 1] - target) < Math.abs(t[best] - target)) best = lo - 1;
        return Math.abs(t[best] - target) <= tolMs ? g[best] : null;
    }
}
