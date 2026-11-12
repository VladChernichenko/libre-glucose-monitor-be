package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.ActivityGains;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.ProteinGluconeogenesis;

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
 * assembly (history windows, regime, CGM trend) is done <b>once</b> in the
 * constructor; each replay only re-runs the ODE with freshly scaled parameters. Anchors are strided
 * and capped ({@link Config#maxAnchors}) to keep a full calibration well under a second.</p>
 */
public final class PredictionReplayEngine implements AnchorSampleSource {

    private static final long BASAL_LOOKBACK_MS =
            che.glucosemonitorbe.hovorka.BasalInsulinResolver.LOOKBACK_HOURS * 3600_000L;

    /** Minutes of CGM history the anchor trend is taken over. */
    private static final int TREND_WINDOW_MIN = 15;

    /** CGM reading in the replay window. */
    public record Reading(long epochMs, double mmol) {}

    /**
     * A logged event (meal and/or bolus, or a long-acting basal note).
     *
     * <p>{@code rescue} marks a fast-acting hypo treatment. It has to travel with the event: the
     * replay engine only ever sees this flattened form, never the {@code Note} it came from, so
     * without it a 15 g dextrose tablet was replayed on the user's 240-minute mixed-meal curve. The
     * fit then met a sharp real glucose rise it could not explain and absorbed the error into
     * {@code agScale}/{@code isfScale} - parameters that go on to shape <em>every</em> prediction
     * for that user.
     *
     * <p>{@code gi} is the meal's estimated glycemic index, 0 when the note carries none. Like the
     * macros it has to travel on the event, or a logged low-GI meal is replayed at the default GI
     * while the live forecast absorbs it at its own.
     */
    public record Event(long epochMs, double carbs, double insulin, boolean longActing,
                        double protein, double fat, double fiber, boolean rescue, double gi) {

        /** An event with no glycemic-index estimate. */
        public Event(long epochMs, double carbs, double insulin, boolean longActing,
                     double protein, double fat, double fiber, boolean rescue) {
            this(epochMs, carbs, insulin, longActing, protein, fat, fiber, rescue, 0.0);
        }

        /**
         * Non-rescue event. For sources with no notion of a hypo treatment - the HUPA-UCM and
         * AZT1D public datasets - where "not a rescue" is a fact about the data, not a default.
         */
        public Event(long epochMs, double carbs, double insulin, boolean longActing,
                     double protein, double fat, double fiber) {
            this(epochMs, carbs, insulin, longActing, protein, fat, fiber, false, 0.0);
        }

        /** The same event as the live path sees it with nutrition-aware prediction switched off. */
        public Event withoutNutrition() {
            return new Event(epochMs, carbs, insulin, longActing, 0.0, 0.0, 0.0, rescue, 0.0);
        }
    }

    /** Replay configuration. */
    public static final class Config {
        /** Prediction horizon per anchor [min]. Shorter than the live 4-8 h path keeps the fit fast
         *  and focused on the well-identified early curve. */
        public int    horizonMin      = 120;
        /** Minutes between anchors. Coarser than the live 5-min cadence - anchors are highly
         *  correlated, so a dense stride adds cost without information. */
        public int    strideMin       = 30;
        /** CGM alignment tolerance when matching an anchor or horizon to a reading [ms]. */
        public long   alignToleranceMs = 300_000; // ±5 min
        /** Hard cap on anchors; if the window yields more they are evenly subsampled. */
        public int    maxAnchors      = 250;
        /**
         * Add the delayed protein glucose, through the same {@link ProteinGluconeogenesis} the live
         * forecast uses (protein only - fat acts through the ileal brake, not as carbs).
         */
        public boolean fpuEquiv       = true;
        /** Duration of action of the user's long-acting insulin [hours] (mirrors the live forecast). */
        public double basalDurationHours = che.glucosemonitorbe.hovorka.BasalInsulinResolver.DEFAULT_DURATION_HOURS;
        /** Horizons (min) to emit samples at - must be a subset of the model's emission schedule. */
        public int[]  sampleHorizons  = {30, 60, 90, 120};
    }

    private final HovorkaGlucosePredictionService predictor;
    private final HovorkaParameters baseParams;
    private final RapidInsulinIobParameters rapidIob;
    private final UserSettingsDTO settings;
    private final UUID userId;
    private final Config cfg;
    private final che.glucosemonitorbe.hovorka.ActivityProvider activityProvider;
    /**
     * The user's UTC offset, defining the clock every replayed timestamp lives on.
     *
     * <p>Anchors are built on the user's <b>local wall clock</b>, because the replay drives the same
     * {@link HovorkaGlucosePredictionService} the live dashboard drives, and the live path is fed the
     * client's local wall time. Running the replay on UTC instead made the fitted
     * {@link AnchorSample#hourOfDay()} a UTC hour while {@code DigitalTwinResidualProvider} applies
     * the grid at a local hour - shifting every learned time-of-day correction by this offset.</p>
     */
    private final java.time.ZoneId userZone;

    private final long[] cgmT;
    private final double[] cgmG;
    private final List<AnchorContext> anchors;

    /** Per-anchor inputs, assembled once (scale-independent). */
    private record AnchorContext(
            double g0, LocalDateTime now,
            List<CarbsEntry> carbs, List<InsulinDose> insulin, List<Note> longActing,
            Regime regime, HovorkaParameters params, double trend) {}

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
        this(predicted, baseParams, rapidIob, settings, userId, cgm, events, cfg,
                che.glucosemonitorbe.hovorka.ActivityProvider.NONE, java.time.ZoneOffset.UTC);
    }

    /** As above, but with an {@link che.glucosemonitorbe.hovorka.ActivityProvider} so the replayed
     *  predictions account for logged activity (NONE = the un-modulated model). */
    public PredictionReplayEngine(HovorkaGlucosePredictionService predicted, HovorkaParameters baseParams,
                                  RapidInsulinIobParameters rapidIob, UserSettingsDTO settings,
                                  UUID userId, List<Reading> cgm, List<Event> events, Config cfg,
                                  che.glucosemonitorbe.hovorka.ActivityProvider activityProvider) {
        this(predicted, baseParams, rapidIob, settings, userId, cgm, events, cfg,
                activityProvider, java.time.ZoneOffset.UTC);
    }

    /** Replay on the user's local clock (see {@link #userZone}); NONE activity provider. */
    public PredictionReplayEngine(HovorkaGlucosePredictionService predicted, HovorkaParameters baseParams,
                                  RapidInsulinIobParameters rapidIob, UserSettingsDTO settings,
                                  UUID userId, List<Reading> cgm, List<Event> events, Config cfg,
                                  java.time.ZoneId userZone) {
        this(predicted, baseParams, rapidIob, settings, userId, cgm, events, cfg,
                che.glucosemonitorbe.hovorka.ActivityProvider.NONE, userZone);
    }

    /** Full form: activity provider and the user's UTC offset. */
    public PredictionReplayEngine(HovorkaGlucosePredictionService predicted, HovorkaParameters baseParams,
                                  RapidInsulinIobParameters rapidIob, UserSettingsDTO settings,
                                  UUID userId, List<Reading> cgm, List<Event> events, Config cfg,
                                  che.glucosemonitorbe.hovorka.ActivityProvider activityProvider,
                                  java.time.ZoneId userZone) {
        this.userZone = userZone;
        this.predictor = predicted;
        this.baseParams = baseParams;
        this.rapidIob = rapidIob;
        this.settings = settings;
        this.userId = userId;
        this.cfg = cfg;
        this.activityProvider = activityProvider;

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
        return replay(scales, ActivityGains.NEUTRAL);
    }

    @Override
    public List<AnchorSample> replay(TwinScales scales, ActivityGains gains) {
        TwinScales s = scales.clamped();
        List<AnchorSample> out = new ArrayList<>();
        for (AnchorContext a : anchors) {
            HovorkaParameters p = applyScales(a.params(), s);
            List<PredictionPointDTO> curve = predictor.buildPredictionPath(
                    p, rapidIob, settings, a.g0(), a.now(), a.carbs(), a.insulin(), a.longActing(),
                    userId, cfg.horizonMin, activityProvider, cfg.basalDurationHours, gains,
                    s.isfScale(), a.trend());
            long t0 = toEpochMs(a.now(), userZone);
            for (PredictionPointDTO pt : curve) {
                int h = (int) Duration.between(a.now(), pt.getTimestamp()).toMinutes();
                if (!isSampleHorizon(h)) continue;
                Double actual = nearest(cgmT, cgmG, t0 + h * 60_000L, cfg.alignToleranceMs);
                if (actual == null) continue;
                // Unrounded: the fit differentiates this numerically with a 1e-3 step, and a value
                // rounded to 0.1 mmol/L does not move under a step that small.
                double predicted = pt.getRawPredictedGlucose() != null
                        ? pt.getRawPredictedGlucose() : pt.getPredictedGlucose();
                out.add(new AnchorSample(h, predicted, actual, a.g0(),
                        a.regime(), pt.getTimestamp().getHour()));
            }
        }
        return out;
    }

    // -- Anchor preparation ------------------------------------------------------

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
            LocalDateTime now = toLdt(t0, userZone);

            List<CarbsEntry> carbs = new ArrayList<>();
            List<InsulinDose> insulin = new ArrayList<>();
            List<Note> longActing = new ArrayList<>();
            boolean carbActive = false, insulinActive = false;

            for (Event n : events) {
                long age = t0 - n.epochMs();
                boolean inPast   = age >= 0 && age <= 8 * 3600_000L;
                boolean inFuture = age < 0 && -age <= horizonMs;
                if (n.longActing()) {
                    if (age >= 0 && age <= BASAL_LOOKBACK_MS) longActing.add(makeLongActing(n, userZone));
                    continue;
                }
                if (!inPast && !inFuture) continue;

                // Same entries the dashboard builds: a meal carries its GI, fiber, protein and fat
                // into the gut model, and a carb-free note with macros (a salad or eggs eaten first)
                // still counts, because its fiber and protein/fat act on the carbs that follow.
                boolean hasMacros = n.protein() > 0 || n.fat() > 0 || n.fiber() > 0;
                if (n.carbs() > 0 || (hasMacros && !n.rescue())) {
                    CarbsEntry entry = CarbsEntry.builder()
                            .timestamp(toLdt(n.epochMs(), userZone)).carbs(n.carbs()).build();
                    if (n.rescue()) {
                        // The marker is what makes the gut model use the rescue tMaxG rather than
                        // the user's own; a rescue has one fixed profile and no macros.
                        RescueCarbProfile.mark(entry);
                    } else {
                        if (n.gi() > 0) entry.setEstimatedGi(n.gi());
                        entry.setFiber(n.fiber());
                        entry.setProtein(n.protein());
                        entry.setFat(n.fat());
                    }
                    carbs.add(entry);
                }
                if (n.insulin() > 0) {
                    insulin.add(InsulinDose.builder().timestamp(toLdt(n.epochMs(), userZone))
                            .units(n.insulin()).type(InsulinDose.InsulinType.BOLUS).build());
                }

                boolean active = age <= 3 * 3600_000L && -age <= horizonMs;
                if (active) {
                    if (n.carbs() > 0 || n.protein() > 0 || n.fat() > 0) carbActive = true;
                    if (n.insulin() > 0) insulinActive = true;
                }
            }

            if (cfg.fpuEquiv) {
                carbs = ProteinGluconeogenesis.withDelayedProteinGlucose(carbs);
            }

            Regime regime = carbActive ? Regime.MEAL : (insulinActive ? Regime.CORRECTION : Regime.FASTING);

            // CGM trend at the anchor, as the dashboard receives it from the client: the slope
            // over the preceding reading window. 0 when there is no earlier reading to take it from.
            Double before = nearest(cgmT, cgmG, t0 - TREND_WINDOW_MIN * 60_000L, cfg.alignToleranceMs);
            double trend = before == null ? 0.0 : (g0 - before) / TREND_WINDOW_MIN;
            result.add(new AnchorContext(g0, now, carbs, insulin, longActing, regime, baseParams, trend));
        }
        return result;
    }

    // -- Parameter helpers -------------------------------------------------------

    /**
     * Apply the A_G and EGP₀ scales to a parameter set. tMaxGScale stays neutral, and the ISF scale
     * is not applied here: it is passed to the predictor, which applies it per dose - a scale put on
     * {@code p.isf()} would be ignored for every user whose settings carry an ISF.
     */
    private static HovorkaParameters applyScales(HovorkaParameters p, TwinScales s) {
        TwinScales c = s.clamped();
        return new HovorkaParameters(
                p.vG(), p.f01(),
                p.egpNet() * c.egpScale(),
                p.egp0()   * c.egpScale(),
                p.k12(), p.k21(),
                p.tMaxG(), p.aG() * c.agScale(), p.isf(), p.weightKg());
    }

    private boolean isSampleHorizon(int h) {
        for (int sh : cfg.sampleHorizons) if (sh == h) return true;
        return false;
    }

    private static Note makeLongActing(Event n, java.time.ZoneId userZone) {
        Note note = new Note();
        note.setTimestamp(toLdt(n.epochMs(), userZone));
        note.setInsulin(n.insulin() > 0 ? n.insulin() : 10.0);
        note.setType(Note.TYPE_LONG_ACTING);
        return note;
    }

    /** Instant -> the wall-clock time the user saw on their own device. */
    public static LocalDateTime toLdt(long epochMs, java.time.ZoneId userZone) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), userZone);
    }

    /**
     * The user's wall-clock time -> the instant it actually happened.
     *
     * <p>{@code notes.timestamp} holds local wall time (the client sends naive local time and the
     * column round-trips it unchanged), while {@code cgm_readings.date_timestamp} holds a true UTC
     * epoch. Converting a note's wall time with {@code ZoneOffset.UTC} - which the calibrator did -
     * placed every meal and bolus {@code userOffset} away from the glucose response it caused.</p>
     */
    public static long toEpochMs(LocalDateTime localWallTime, java.time.ZoneId userZone) {
        // atZone (not toInstant(offset)) so the offset is resolved for THIS instant: a 30-day
        // calibration window spans a DST transition twice a year, and a fixed offset would convert
        // one side of it an hour wrong. Gaps/overlaps resolve by the java.time rules.
        return localWallTime.atZone(userZone).toInstant().toEpochMilli();
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
