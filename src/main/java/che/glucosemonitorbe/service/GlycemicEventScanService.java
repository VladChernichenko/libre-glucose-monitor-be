package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.hovorka.ActivityProvider;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.NotesActivityProvider;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Detects windows where glucose moved in a way the logged inputs (COB/IOB) do not explain, using the
 * physiological model residual (see {@code specs/glycemic-event-detector.md}). Runs the raw Hovorka
 * prediction over a recent CGM window, finds a sustained same-sign residual that exceeds an adaptive
 * (robust-σ) threshold, classifies it, and persists an {@link GlycemicEvent} (deduped against any
 * open flag). User operations (list / confirm / dismiss) live in {@link GlycemicEventService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlycemicEventScanService {

    private static final double MGDL_PER_MMOL = che.glucosemonitorbe.domain.GlucoseConversion.MGDL_PER_MMOL;
    private static final String SEED_EMAIL_PATTERN = "azt1d-subject-%@dataset.local";
    private static final int WARMUP_LOOKBACK_HOURS = 8;

    private final CgmReadingRepository cgmReadingRepository;
    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final GlycemicEventRepository flagRepository;
    private final HovorkaParameterService paramService;
    private final HovorkaOdeSolver odeSolver;
    private final DallaManGutModel gutModel;
    private final BasalInsulinResolver basalResolver;
    private final UserInsulinPreferencesService insulinPrefsService;
    private final UserSettingsService userSettingsService;
    private final FeatureToggleConfig featureToggleConfig;
    private final GlycemicEventHistoryRecorder historyRecorder;

    /** Length of the CGM window scanned each pass [min]. */
    @Value("${app.glycemic-events.window-minutes:180}")
    private int windowMinutes;
    /** Minimum aligned residual points needed to estimate σ and run the scan. */
    @Value("${app.glycemic-events.min-readings:24}")
    private int minReadings;
    /** Robust-σ multiple the sustained mean residual must exceed to flag. */
    @Value("${app.glycemic-events.sigma-multiple:2.0}")
    private double sigmaMultiple;
    /** Minimum duration of the sustained run [min] - rejects transient sensor artifacts. */
    @Value("${app.glycemic-events.persistence-minutes:45}")
    private int persistenceMinutes;
    /** Tolerance when aligning a predicted point to an actual CGM reading [ms]. */
    @Value("${app.glycemic-events.align-tolerance-ms:150000}")
    private long alignToleranceMs;
    /** How far before the run a matching logged event may sit and still count as "logged" [min]. */
    @Value("${app.glycemic-events.match-lead-minutes:45}")
    private int matchLeadMinutes;

    /** Raw predictor (no twin overlay, no residual correction) - the calibration-consistent baseline. */
    private HovorkaGlucosePredictionService rawPredictor;

    @PostConstruct
    void initPredictor() {
        this.rawPredictor = new HovorkaGlucosePredictionService(
                paramService, odeSolver, basalResolver, insulinPrefsService, gutModel,
                userSettingsService, PredictionResidualProvider.NONE);
    }

    /** Aggregate outcome of a scan pass. */
    public record ScanSummary(int scanned, int flagged, int skipped, int failed) {}

    /**
     * Note -> the warm-up / forward-prediction carb input for the residual scan.
     *
     * <p>A {@code hypo_treatment} note carries the rescue marker through
     * {@link RescueCarbProfile#mark}. Left unmarked, the raw forward prediction absorbs a 15 g
     * dextrose tablet on the user's 240-minute mixed-meal curve, under-predicts the recovery rise,
     * and produces a large positive residual - flagging a <em>logged</em> hypo recovery as unlogged
     * food. That flag then feeds back into the digital-twin fit as a down-weighted window.
     *
     * <p>Package-private and static so the marker's survival on this path is directly testable;
     * the scan has no other seam that exposes the entries it builds.
     */
    static CarbsEntry toCarbsEntry(Note n) {
        CarbsEntry entry = CarbsEntry.builder()
                .timestamp(n.getTimestamp()).carbs(n.getCarbs()).build();
        return n.isHypoTreatment() ? RescueCarbProfile.mark(entry) : entry;
    }

    // -- Scan -----------------------------------------------------------------

    /** Scan every real (non-seed) user. No-op unless the feature is enabled. */
    public ScanSummary scanAllRealUsers() {
        if (!featureToggleConfig.isGlycemicEventDetectionEnabled()) {
            return new ScanSummary(0, 0, 0, 0);
        }
        List<User> users = userRepository.findByEmailNotLike(SEED_EMAIL_PATTERN);
        int scanned = 0, flagged = 0, skipped = 0, failed = 0;
        for (User user : users) {
            try {
                Optional<GlycemicEvent> flag = scanUser(user.getId());
                scanned++;
                if (flag.isPresent()) flagged++; else skipped++;
            } catch (Exception e) {
                failed++;
                log.warn("Unlogged-event scan failed for user {}: {}", user.getId(), e.getMessage());
            }
        }
        ScanSummary summary = new ScanSummary(scanned, flagged, skipped, failed);
        log.info("Unlogged-event scan: scanned={}, flagged={}, skipped={}, failed={}",
                scanned, flagged, skipped, failed);
        return summary;
    }

    /**
     * Scan one user's recent window. Returns the created/updated flag when a sustained unexplained
     * residual is found, else empty (feature off, too little data, or nothing unexplained).
     */
    @Transactional
    public Optional<GlycemicEvent> scanUser(UUID userId) {
        if (!featureToggleConfig.isGlycemicEventDetectionEnabled()) return Optional.empty();

        // Run on the user's own clock. notes.timestamp holds local wall time, while CGM rows hold
        // true UTC epochs - comparing a local-wall note against a UTC-wall CGM window put every
        // logged meal `offset` hours outside the window that its own carbs caused, so a logged
        // event was mislabelled UNLOGGED_* and its CGM data was then excluded from digital-twin
        // calibration (audit F26). Same clock choice as the calibrator and the live dashboard.
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        java.time.ZoneId userZone = resolveUserZone(settings);

        LocalDateTime end = LocalDateTime.now(userZone);
        LocalDateTime start = end.minusMinutes(windowMinutes);
        long startMs = toEpochMs(start, userZone);
        long endMs = toEpochMs(end, userZone);

        List<CgmReading> readings = cgmReadingRepository
                .findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(userId, startMs, endMs);
        if (readings.size() < minReadings) return Optional.empty();

        // Events for warm-up + prediction inputs (and for the matching-event check).
        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(
                userId, start.minusHours(WARMUP_LOOKBACK_HOURS), end);
        List<CarbsEntry> carbs = new ArrayList<>();
        List<InsulinDose> insulin = new ArrayList<>();
        List<Note> longActing = new ArrayList<>();
        for (Note n : notes) {
            if (n.isLongActing()) { longActing.add(n); continue; }
            if (n.getCarbs() != null && n.getCarbs() > 0) {
                carbs.add(toCarbsEntry(n));
            }
            if (n.getInsulin() != null && n.getInsulin() > 0) {
                insulin.add(InsulinDose.builder().timestamp(n.getTimestamp())
                        .units(n.getInsulin()).type(InsulinDose.InsulinType.BOLUS).build());
            }
        }

        // Raw forward prediction anchored at the window start.
        CgmReading first = readings.get(0);
        if (first.getSgv() == null) return Optional.empty();
        LocalDateTime anchorTime = toLdt(first.getDateTimestamp(), userZone);
        double g0 = first.getSgv() / MGDL_PER_MMOL;
        HovorkaParameters baseParams = paramService.buildRawForUser(userId);
        RapidInsulinIobParameters rapidIob = insulinPrefsService.getRapidIobParameters(userId);
        // Make the residual activity-aware so an exercise-driven drop from a logged activity is
        // explained (not mistaken for unlogged insulin). Inert when activity logging is off.
        ActivityProvider activity = featureToggleConfig.isActivityLoggingEnabled()
                ? NotesActivityProvider.fromNotes(notes) : ActivityProvider.NONE;
        List<PredictionPointDTO> predicted = rawPredictor.buildPredictionPath(
                baseParams, rapidIob, settings, g0, anchorTime, carbs, insulin, longActing,
                userId, windowMinutes, activity);

        // Align predicted -> actual; residual = actual − predicted [mmol/L].
        TreeMap<Long, Double> predByMs = new TreeMap<>();
        for (PredictionPointDTO pt : predicted) {
            if (pt.getPredictedGlucose() != null) predByMs.put(toEpochMs(pt.getTimestamp(), userZone), pt.getPredictedGlucose());
        }
        List<long[]> tMs = new ArrayList<>();
        List<Double> resid = new ArrayList<>();
        for (CgmReading r : readings) {
            if (r.getSgv() == null || r.getDateTimestamp() == null) continue;
            Double p = nearest(predByMs, r.getDateTimestamp(), alignToleranceMs);
            if (p == null) continue;
            tMs.add(new long[]{r.getDateTimestamp()});
            resid.add(r.getSgv() / MGDL_PER_MMOL - p);
        }
        if (resid.size() < minReadings) return Optional.empty();

        double sigma = robustScale(resid);
        double threshold = sigmaMultiple * sigma;

        // Strongest sustained same-sign run above threshold.
        Run best = strongestRun(tMs, resid, threshold, persistenceMinutes);
        if (best == null) return Optional.empty();

        return Optional.of(persistFlag(userId, best, sigma, notes, userZone));
    }

    // -- Detection helpers ------------------------------------------------------

    record Run(long startMs, long endMs, double mean) {}

    /** Strongest sustained same-sign run whose mean |residual| >= threshold and duration >= persistence. */
    static Run strongestRun(List<long[]> tMs, List<Double> resid, double threshold, int persistenceMinutes) {
        int n = resid.size();
        Run best = null;
        int i = 0;
        while (i < n) {
            int sign = resid.get(i) >= 0 ? 1 : -1;
            int j = i;
            double sum = 0.0;
            while (j < n && (resid.get(j) >= 0 ? 1 : -1) == sign) { sum += resid.get(j); j++; }
            int count = j - i;
            long runStart = tMs.get(i)[0], runEnd = tMs.get(j - 1)[0];
            double durMin = (runEnd - runStart) / 60_000.0;
            double mean = sum / count;
            if (durMin >= persistenceMinutes && Math.abs(mean) >= threshold
                    && (best == null || Math.abs(mean) > Math.abs(best.mean()))) {
                best = new Run(runStart, runEnd, mean);
            }
            i = j;
        }
        return best;
    }

    private GlycemicEvent persistFlag(UUID userId, Run run, double sigma, List<Note> notes,
                                          java.time.ZoneId userZone) {
        boolean rise = run.mean() > 0;
        LocalDateTime wStart = toLdt(run.startMs(), userZone);
        LocalDateTime wEnd = toLdt(run.endMs(), userZone);
        LocalDateTime matchFrom = wStart.minusMinutes(matchLeadMinutes);

        boolean matchingLogged = notes.stream().anyMatch(nt -> {
            LocalDateTime t = nt.getTimestamp();
            if (t == null || t.isBefore(matchFrom) || t.isAfter(wEnd)) return false;
            return rise ? (nt.getCarbs() != null && nt.getCarbs() > 0)
                        : (nt.getInsulin() != null && nt.getInsulin() > 0);
        });

        Category category = rise
                ? (matchingLogged ? Category.UNDER_ESTIMATED_FOOD : Category.UNLOGGED_FOOD)
                : (matchingLogged ? Category.UNDER_ESTIMATED_INSULIN : Category.UNLOGGED_INSULIN);
        Direction direction = rise ? Direction.RISE : Direction.FALL;
        double sigmaMult = sigma > 0 ? Math.abs(run.mean()) / sigma : 0.0;

        // Dedupe: update an existing OPEN flag that overlaps this window, else create.
        GlycemicEvent flag = flagRepository
                .findByUserIdAndStateIn(userId, List.of(State.OPEN)).stream()
                .filter(f -> overlaps(f, wStart, wEnd))
                .findFirst()
                .orElseGet(() -> GlycemicEvent.builder().userId(userId).state(State.OPEN).build());

        boolean isNew = flag.getId() == null;
        Category before = flag.getCategory();
        flag.setCategory(category);
        flag.setDetectorCategory(category);
        flag.setPhase(GlycemicEvent.Phase.FINAL);
        flag.setOnsetAt(wStart);
        flag.setDirection(direction);
        flag.setWindowStart(wStart);
        flag.setWindowEnd(wEnd);
        flag.setMeanResidualMmol(round2(run.mean()));
        flag.setSigmaMultiple(round2(sigmaMult));
        flag.setUpdatedAt(LocalDateTime.now());
        GlycemicEvent saved = flagRepository.save(flag);
        if (isNew) {
            historyRecorder.record(saved, GlycemicEventHistory.Kind.DETECTED, null,
                    GlycemicEventHistory.Actor.SYSTEM, "rule:legacy");
        } else if (before != category) {
            historyRecorder.record(saved, GlycemicEventHistory.Kind.UPDATED, before,
                    GlycemicEventHistory.Actor.SYSTEM, "rule:legacy");
        }
        return saved;
    }

    private static boolean overlaps(GlycemicEvent f, LocalDateTime start, LocalDateTime end) {
        return !f.getWindowStart().isAfter(end) && !start.isAfter(f.getWindowEnd());
    }

    // -- Math / time helpers -----------------------------------------------------

    /** Robust spread: 1.4826*MAD, floored at 0.3 mmol/L (CGM sensor noise) - matches the calibrator. */
    static double robustScale(List<Double> values) {
        if (values.isEmpty()) return 0.3;
        double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
        double median = median(a.clone());
        double[] dev = new double[a.length];
        for (int i = 0; i < a.length; i++) dev[i] = Math.abs(a[i] - median);
        return Math.max(0.3, 1.4826 * median(dev));
    }

    private static double median(double[] a) {
        Arrays.sort(a);
        int n = a.length;
        if (n == 0) return 0.0;
        return (n % 2 == 1) ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }

    /** Nearest value in a time->value map within tolerance, or null. */
    private static Double nearest(TreeMap<Long, Double> map, long target, long tolMs) {
        Map.Entry<Long, Double> floor = map.floorEntry(target);
        Map.Entry<Long, Double> ceil = map.ceilingEntry(target);
        Map.Entry<Long, Double> best = null;
        long bestDiff = Long.MAX_VALUE;
        if (floor != null) { best = floor; bestDiff = target - floor.getKey(); }
        if (ceil != null && (ceil.getKey() - target) < bestDiff) { best = ceil; bestDiff = ceil.getKey() - target; }
        return (best != null && bestDiff <= tolMs) ? best.getValue() : null;
    }

    /** @see UserZones#resolve(String, Integer) */
    private static java.time.ZoneId resolveUserZone(UserSettingsDTO settings) {
        if (settings == null) return ZoneOffset.UTC;
        return UserZones.resolve(settings.getTimezone(), settings.getUtcOffsetMinutes());
    }

    private static LocalDateTime toLdt(long epochMs, java.time.ZoneId zone) {
        return UserZones.toWallTime(epochMs, zone);
    }

    private static long toEpochMs(LocalDateTime ldt, java.time.ZoneId zone) {
        return UserZones.toEpochMs(ldt, zone);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
