package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.UserDigitalTwin;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserDigitalTwinRepository;
import che.glucosemonitorbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates the nightly digital-twin calibration for a user: loads their CGM + notes history,
 * replays the Hovorka model over it, fits {@link DigitalTwinCalibrator scales + a residual grid}
 * against the predicted-vs-actual error, and persists a {@link UserDigitalTwin}.
 *
 * <p>The twin only becomes {@code applied} (active for predictions) when it beats the un-calibrated
 * model on a held-out validation window — otherwise the record is stored with {@code applied=false}
 * for observability and predictions are left unchanged.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalTwinCalibrationService {

    /** How much history to calibrate on. */
    private static final int LOOKBACK_DAYS = 30;
    /** Fraction of the window used to fit; the remainder scores accuracy out-of-sample. */
    private static final double TRAIN_FRACTION = 0.8;
    /** Skip users with fewer CGM readings than this — not enough signal to fit against. */
    private static final int MIN_CGM_READINGS = 200;
    private static final double MGDL_PER_MMOL = 18.0182;

    private static final String MACRO_FMT = "\"%s\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)";

    private final CgmReadingRepository cgmReadingRepository;
    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final UserDigitalTwinRepository twinRepository;

    private final HovorkaParameterService paramService;
    private final HovorkaOdeSolver odeSolver;
    private final DallaManGutModel gutModel;
    private final BasalInsulinResolver basalResolver;
    private final UserInsulinPreferencesService insulinPrefsService;
    private final UserSettingsService userSettingsService;

    private final DigitalTwinService digitalTwinService;
    private final FeatureToggleConfig featureToggleConfig;

    /**
     * Seed users are non-loginable AZT1D dataset fixtures ({@code azt1d-subject-N@dataset.local}) —
     * excluded from real-user batches so we never spend the nightly compute budget calibrating them.
     */
    private static final String SEED_EMAIL_PATTERN = "azt1d-subject-%@dataset.local";

    /** Aggregate outcome of a batch calibration pass. */
    public record BatchSummary(int totalUsers, int attempted, int applied, int skipped, int failed) {}

    /**
     * Calibrate every real (non-seed) user. Nightly entry point and on-demand backfill trigger.
     * No-op (empty summary) unless the {@code digital-twin-enabled} feature flag is on.
     */
    public BatchSummary calibrateAllRealUsers() {
        if (!featureToggleConfig.isDigitalTwinEnabled()) {
            log.debug("Digital twin disabled — skipping real-user calibration");
            return new BatchSummary(0, 0, 0, 0, 0);
        }
        List<User> users = userRepository.findByEmailNotLike(SEED_EMAIL_PATTERN);
        int attempted = 0, applied = 0, skipped = 0, failed = 0;
        for (User user : users) {
            try {
                DigitalTwinCalibrator.Result r = calibrateUser(user.getId());
                if (r == null) {
                    skipped++;   // feature off mid-run or not enough CGM history to attempt a fit
                } else {
                    attempted++;
                    if (r.improved()) applied++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Digital-twin calibration failed for user {}: {}", user.getId(), e.getMessage());
            }
        }
        BatchSummary summary = new BatchSummary(users.size(), attempted, applied, skipped, failed);
        log.info("Digital-twin batch (real users): total={}, attempted={}, applied={}, "
                + "skipped(insufficient data)={}, failed={}",
                summary.totalUsers(), attempted, applied, skipped, failed);
        return summary;
    }

    /**
     * Calibrate one user and persist the outcome. Returns the calibration result, or {@code null}
     * when the feature is disabled or the user lacks enough data to attempt a fit.
     */
    @Transactional
    public DigitalTwinCalibrator.Result calibrateUser(UUID userId) {
        if (!featureToggleConfig.isDigitalTwinEnabled()) return null;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusDays(LOOKBACK_DAYS);
        long cutoffMs = windowStart.toInstant(ZoneOffset.UTC).toEpochMilli();

        // ── Load CGM ──────────────────────────────────────────────────────────
        List<CgmReading> readings = cgmReadingRepository
                .findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(userId, cutoffMs);
        if (readings.size() < MIN_CGM_READINGS) {
            log.debug("User {}: only {} CGM readings in last {}d — skipping calibration",
                    userId, readings.size(), LOOKBACK_DAYS);
            return null;
        }
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>(readings.size());
        for (CgmReading r : readings) {
            if (r.getSgv() == null || r.getDateTimestamp() == null) continue;
            cgm.add(new PredictionReplayEngine.Reading(r.getDateTimestamp(), r.getSgv() / MGDL_PER_MMOL));
        }

        // ── Load events (meals / boluses / basal) ───────────────────────────────
        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(userId, windowStart, now);
        List<PredictionReplayEngine.Event> events = new ArrayList<>(notes.size());
        for (Note n : notes) {
            if (n.getTimestamp() == null) continue;
            long epochMs = n.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli();
            String profile = n.getNutritionProfile();
            events.add(new PredictionReplayEngine.Event(
                    epochMs,
                    n.getCarbs()   != null ? n.getCarbs()   : 0.0,
                    n.getInsulin() != null ? n.getInsulin() : 0.0,
                    n.isLongActing(),
                    macro(profile, "protein"), macro(profile, "fat"), macro(profile, "fiber")));
        }

        // ── Build raw predictor + base params + one-time IOB/settings snapshot ──
        HovorkaParameters baseParams = paramService.buildRawForUser(userId);
        RapidInsulinIobParameters rapidIob = insulinPrefsService.getRapidIobParameters(userId);
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        HovorkaGlucosePredictionService rawPredictor = new HovorkaGlucosePredictionService(
                paramService, odeSolver, basalResolver, insulinPrefsService, gutModel,
                userSettingsService, PredictionResidualProvider.NONE);

        // ── Temporal split: fit on the earlier part, score on the later part ────
        int boundary = (int) Math.floor(cgm.size() * TRAIN_FRACTION);
        List<PredictionReplayEngine.Reading> trainCgm = cgm.subList(0, boundary);
        List<PredictionReplayEngine.Reading> valCgm   = cgm.subList(boundary, cgm.size());

        PredictionReplayEngine.Config cfg = new PredictionReplayEngine.Config();
        PredictionReplayEngine train = new PredictionReplayEngine(
                rawPredictor, baseParams, rapidIob, settings, userId, trainCgm, events, cfg);
        PredictionReplayEngine val = new PredictionReplayEngine(
                rawPredictor, baseParams, rapidIob, settings, userId, valCgm, events, cfg);

        // ── Fit ─────────────────────────────────────────────────────────────────
        DigitalTwinCalibrator.Result result = new DigitalTwinCalibrator().calibrate(train, val);

        persist(userId, result, now);
        digitalTwinService.invalidate(userId);
        log.info("Digital-twin calibration user={} → {} (isf×{}, aG×{})",
                userId, result.status(),
                round(result.scales().isfScale()), round(result.scales().agScale()));
        return result;
    }

    /** Current twin status for a user (for the API / UI). */
    @Transactional(readOnly = true)
    public che.glucosemonitorbe.dto.DigitalTwinStatusDTO getStatus(UUID userId) {
        return twinRepository.findByUserId(userId)
                .map(t -> che.glucosemonitorbe.dto.DigitalTwinStatusDTO.builder()
                        .applied(Boolean.TRUE.equals(t.getApplied()))
                        .isfScale(t.getIsfScale())
                        .agScale(t.getAgScale())
                        .maeBaseline(t.getMaeBaseline())
                        .maeCalibrated(t.getMaeCalibrated())
                        .improvementPct(t.getImprovementPct())
                        .trainSamples(t.getTrainSamples())
                        .valSamples(t.getValSamples())
                        .confidence(t.getConfidence())
                        .status(t.getStatus())
                        .fittedAt(t.getFittedAt())
                        .neverCalibrated(false)
                        .build())
                .orElseGet(() -> che.glucosemonitorbe.dto.DigitalTwinStatusDTO.builder()
                        .applied(false)
                        .isfScale(1.0).agScale(1.0)
                        .neverCalibrated(true)
                        .status("not yet calibrated")
                        .build());
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private void persist(UUID userId, DigitalTwinCalibrator.Result r, LocalDateTime now) {
        UserDigitalTwin twin = twinRepository.findByUserId(userId)
                .orElseGet(() -> UserDigitalTwin.builder().userId(userId).build());

        twin.setIsfScale(r.scales().isfScale());
        twin.setAgScale(r.scales().agScale());
        twin.setTMaxGScale(r.scales().tMaxGScale());
        twin.setEgpScale(r.scales().egpScale());
        twin.setResidualGrid(r.residual().toCsv());
        twin.setUncertaintySdGrid(r.uncertainty().toCsv());
        twin.setApplied(r.improved());
        twin.setMaeBaseline(nanToNull(r.maeBaseline()));
        twin.setMaeCalibrated(nanToNull(r.maeCalibrated()));
        twin.setImprovementPct(round(r.improvementFraction() * 100.0));
        twin.setTrainSamples(r.trainSamples());
        twin.setValSamples(r.valSamples());
        twin.setConfidence(r.confidence());
        twin.setStatus(r.status());
        twin.setFittedAt(now);

        twinRepository.save(twin);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Extract a numeric macro (protein/fat/fiber) from a nutrition_profile JSON blob; 0 if absent. */
    static double macro(String json, String key) {
        if (json == null || json.isEmpty()) return 0.0;
        Matcher m = Pattern.compile(String.format(MACRO_FMT, key)).matcher(json);
        return m.find() ? Math.max(0.0, Double.parseDouble(m.group(1))) : 0.0;
    }

    private static Double nanToNull(double v) {
        return Double.isNaN(v) ? null : round(v);
    }

    private static Double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return Math.round(v * 1000.0) / 1000.0;
    }
}
