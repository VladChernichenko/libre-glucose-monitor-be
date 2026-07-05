package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UnloggedEventFlagDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.UnloggedEventFlag;
import che.glucosemonitorbe.entity.UnloggedEventFlag.Category;
import che.glucosemonitorbe.entity.UnloggedEventFlag.Direction;
import che.glucosemonitorbe.entity.UnloggedEventFlag.State;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UnloggedEventFlagRepository;
import che.glucosemonitorbe.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
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
 * physiological model residual (see {@code specs/unlogged-event-detector.md}). Runs the raw Hovorka
 * prediction over a recent CGM window, finds a sustained same-sign residual that exceeds an adaptive
 * (robust-σ) threshold, classifies it, and persists an {@link UnloggedEventFlag} (deduped against any
 * open flag). Also serves the list / confirm / dismiss operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnloggedEventDetectionService {

    private static final double MGDL_PER_MMOL = 18.0182;
    private static final String SEED_EMAIL_PATTERN = "azt1d-subject-%@dataset.local";
    private static final int WARMUP_LOOKBACK_HOURS = 8;

    private final CgmReadingRepository cgmReadingRepository;
    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final UnloggedEventFlagRepository flagRepository;
    private final HovorkaParameterService paramService;
    private final HovorkaOdeSolver odeSolver;
    private final DallaManGutModel gutModel;
    private final BasalInsulinResolver basalResolver;
    private final UserInsulinPreferencesService insulinPrefsService;
    private final UserSettingsService userSettingsService;
    private final FeatureToggleConfig featureToggleConfig;

    /** Length of the CGM window scanned each pass [min]. */
    @Value("${app.unlogged-events.window-minutes:180}")
    private int windowMinutes;
    /** Minimum aligned residual points needed to estimate σ and run the scan. */
    @Value("${app.unlogged-events.min-readings:24}")
    private int minReadings;
    /** Robust-σ multiple the sustained mean residual must exceed to flag. */
    @Value("${app.unlogged-events.sigma-multiple:2.0}")
    private double sigmaMultiple;
    /** Minimum duration of the sustained run [min] — rejects transient sensor artifacts. */
    @Value("${app.unlogged-events.persistence-minutes:45}")
    private int persistenceMinutes;
    /** Tolerance when aligning a predicted point to an actual CGM reading [ms]. */
    @Value("${app.unlogged-events.align-tolerance-ms:150000}")
    private long alignToleranceMs;
    /** How far before the run a matching logged event may sit and still count as "logged" [min]. */
    @Value("${app.unlogged-events.match-lead-minutes:45}")
    private int matchLeadMinutes;
    /** Plausibility caps for a confirm-backfill amount. */
    @Value("${app.unlogged-events.backfill-max-carbs:300}")
    private double maxBackfillCarbs;
    @Value("${app.unlogged-events.backfill-max-insulin:50}")
    private double maxBackfillInsulin;

    /** Raw predictor (no twin overlay, no residual correction) — the calibration-consistent baseline. */
    private HovorkaGlucosePredictionService rawPredictor;

    @PostConstruct
    void initPredictor() {
        this.rawPredictor = new HovorkaGlucosePredictionService(
                paramService, odeSolver, basalResolver, insulinPrefsService, gutModel,
                userSettingsService, PredictionResidualProvider.NONE);
    }

    /** Aggregate outcome of a scan pass. */
    public record ScanSummary(int scanned, int flagged, int skipped, int failed) {}

    // ── Scan ─────────────────────────────────────────────────────────────────

    /** Scan every real (non-seed) user. No-op unless the feature is enabled. */
    public ScanSummary scanAllRealUsers() {
        if (!featureToggleConfig.isUnloggedEventDetectionEnabled()) {
            return new ScanSummary(0, 0, 0, 0);
        }
        List<User> users = userRepository.findByEmailNotLike(SEED_EMAIL_PATTERN);
        int scanned = 0, flagged = 0, skipped = 0, failed = 0;
        for (User user : users) {
            try {
                Optional<UnloggedEventFlag> flag = scanUser(user.getId());
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
    public Optional<UnloggedEventFlag> scanUser(UUID userId) {
        if (!featureToggleConfig.isUnloggedEventDetectionEnabled()) return Optional.empty();

        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = end.minusMinutes(windowMinutes);
        long startMs = toEpochMs(start);
        long endMs = toEpochMs(end);

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
                carbs.add(CarbsEntry.builder().timestamp(n.getTimestamp()).carbs(n.getCarbs()).build());
            }
            if (n.getInsulin() != null && n.getInsulin() > 0) {
                insulin.add(InsulinDose.builder().timestamp(n.getTimestamp())
                        .units(n.getInsulin()).type(InsulinDose.InsulinType.BOLUS).build());
            }
        }

        // Raw forward prediction anchored at the window start.
        CgmReading first = readings.get(0);
        if (first.getSgv() == null) return Optional.empty();
        LocalDateTime anchorTime = toLdt(first.getDateTimestamp());
        double g0 = first.getSgv() / MGDL_PER_MMOL;
        HovorkaParameters baseParams = paramService.buildRawForUser(userId);
        RapidInsulinIobParameters rapidIob = insulinPrefsService.getRapidIobParameters(userId);
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        List<PredictionPointDTO> predicted = rawPredictor.buildPredictionPath(
                baseParams, rapidIob, settings, g0, anchorTime, carbs, insulin, longActing,
                userId, windowMinutes);

        // Align predicted → actual; residual = actual − predicted [mmol/L].
        TreeMap<Long, Double> predByMs = new TreeMap<>();
        for (PredictionPointDTO pt : predicted) {
            if (pt.getPredictedGlucose() != null) predByMs.put(toEpochMs(pt.getTimestamp()), pt.getPredictedGlucose());
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

        return Optional.of(persistFlag(userId, best, sigma, notes));
    }

    // ── Detection helpers ──────────────────────────────────────────────────────

    record Run(long startMs, long endMs, double mean) {}

    /** Strongest sustained same-sign run whose mean |residual| ≥ threshold and duration ≥ persistence. */
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

    private UnloggedEventFlag persistFlag(UUID userId, Run run, double sigma, List<Note> notes) {
        boolean rise = run.mean() > 0;
        LocalDateTime wStart = toLdt(run.startMs());
        LocalDateTime wEnd = toLdt(run.endMs());
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
        UnloggedEventFlag flag = flagRepository
                .findByUserIdAndStateIn(userId, List.of(State.OPEN)).stream()
                .filter(f -> overlaps(f, wStart, wEnd))
                .findFirst()
                .orElseGet(() -> UnloggedEventFlag.builder().userId(userId).state(State.OPEN).build());

        flag.setCategory(category);
        flag.setDirection(direction);
        flag.setWindowStart(wStart);
        flag.setWindowEnd(wEnd);
        flag.setMeanResidualMmol(round2(run.mean()));
        flag.setSigmaMultiple(round2(sigmaMult));
        flag.setUpdatedAt(LocalDateTime.now());
        return flagRepository.save(flag);
    }

    private static boolean overlaps(UnloggedEventFlag f, LocalDateTime start, LocalDateTime end) {
        return !f.getWindowStart().isAfter(end) && !start.isAfter(f.getWindowEnd());
    }

    // ── List / confirm / dismiss ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UnloggedEventFlagDTO> list(UUID userId, State stateFilter) {
        List<UnloggedEventFlag> flags = stateFilter == null
                ? flagRepository.findByUserIdOrderByDetectedAtDesc(userId)
                : flagRepository.findByUserIdAndStateOrderByDetectedAtDesc(userId, stateFilter);
        return flags.stream().map(UnloggedEventFlagDTO::from).toList();
    }

    /**
     * Confirm a flag. When a corrected carbs/insulin amount is supplied it is backfilled as a real
     * note (validated); otherwise the flag is simply marked confirmed.
     */
    @Transactional
    public UnloggedEventFlagDTO confirm(UUID userId, UUID flagId, Double carbs, Double insulin) {
        UnloggedEventFlag flag = requireOpenFlag(userId, flagId);
        boolean backfill = (carbs != null && carbs > 0) || (insulin != null && insulin > 0);
        if (backfill) {
            double c = validateAmount(carbs, maxBackfillCarbs, "carbs");
            double u = validateAmount(insulin, maxBackfillInsulin, "insulin");
            Note note = new Note();
            note.setUserId(userId);
            note.setTimestamp(flag.getWindowStart());
            note.setCarbs(c);
            note.setInsulin(u);
            note.setMeal("Unlogged (backfilled)");
            note.setType(Note.TYPE_NORMAL);
            note.setCreatedAt(LocalDateTime.now());
            note.setUpdatedAt(LocalDateTime.now());
            noteRepository.save(note);
        }
        flag.setState(State.CONFIRMED);
        flag.setResolvedAt(LocalDateTime.now());
        flag.setUpdatedAt(LocalDateTime.now());
        return UnloggedEventFlagDTO.from(flagRepository.save(flag));
    }

    @Transactional
    public UnloggedEventFlagDTO dismiss(UUID userId, UUID flagId) {
        UnloggedEventFlag flag = requireOpenFlag(userId, flagId);
        flag.setState(State.DISMISSED);
        flag.setResolvedAt(LocalDateTime.now());
        flag.setUpdatedAt(LocalDateTime.now());
        return UnloggedEventFlagDTO.from(flagRepository.save(flag));
    }

    private UnloggedEventFlag requireOpenFlag(UUID userId, UUID flagId) {
        UnloggedEventFlag flag = flagRepository.findById(flagId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Flag not found"));
        if (!flag.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flag not found"); // don't leak existence
        }
        if (flag.getState() != State.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Flag already resolved");
        }
        return flag;
    }

    private double validateAmount(Double v, double max, String name) {
        double x = v == null ? 0.0 : v;
        if (x < 0 || x > max) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Invalid backfill " + name + ": " + v);
        }
        return x;
    }

    // ── Math / time helpers ─────────────────────────────────────────────────────

    /** Robust spread: 1.4826·MAD, floored at 0.3 mmol/L (CGM sensor noise) — matches the calibrator. */
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

    /** Nearest value in a time→value map within tolerance, or null. */
    private static Double nearest(TreeMap<Long, Double> map, long target, long tolMs) {
        Map.Entry<Long, Double> floor = map.floorEntry(target);
        Map.Entry<Long, Double> ceil = map.ceilingEntry(target);
        Map.Entry<Long, Double> best = null;
        long bestDiff = Long.MAX_VALUE;
        if (floor != null) { best = floor; bestDiff = target - floor.getKey(); }
        if (ceil != null && (ceil.getKey() - target) < bestDiff) { best = ceil; bestDiff = ceil.getKey() - target; }
        return (best != null && bestDiff <= tolMs) ? best.getValue() : null;
    }

    private static LocalDateTime toLdt(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    private static long toEpochMs(LocalDateTime ldt) {
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
