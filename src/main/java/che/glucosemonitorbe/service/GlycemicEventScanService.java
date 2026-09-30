package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Phase;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.EventClassifier;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.Detection;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Periodic glycemic-event scan (spec section 2): loads one user's recent CGM + notes, runs the pure
 * {@link GlycemicEventDetector}, classifies each detection and persists it (merging into an overlapping
 * OPEN event of the same direction). User operations live in {@link GlycemicEventService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlycemicEventScanService {

    private static final double MGDL_PER_MMOL = GlucoseConversion.MGDL_PER_MMOL;
    private static final String SEED_EMAIL_PATTERN = "azt1d-subject-%@dataset.local";
    private static final int WARMUP_LOOKBACK_HOURS = 8;

    private final CgmReadingRepository cgmReadingRepository;
    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final GlycemicEventRepository eventRepository;
    private final HovorkaParameterService paramService;
    private final UserInsulinPreferencesService insulinPrefsService;
    private final UserSettingsService userSettingsService;
    private final CarbsOnBoardService carbsOnBoardService;
    private final FeatureToggleConfig featureToggleConfig;
    private final GlycemicEventProperties props;
    private final GlycemicEventDetector detector;
    private final GlycemicEventHistoryRecorder historyRecorder;

    /** Aggregate outcome of a scan pass. */
    public record ScanSummary(int scanned, int flagged, int skipped, int failed) {}

    /** Scan every real (non-seed) user. No-op unless the feature is enabled. */
    public ScanSummary scanAllRealUsers() {
        if (!featureToggleConfig.isGlycemicEventDetectionEnabled()) return new ScanSummary(0, 0, 0, 0);
        int scanned = 0, flagged = 0, skipped = 0, failed = 0;
        for (User user : userRepository.findByEmailNotLike(SEED_EMAIL_PATTERN)) {
            try {
                List<GlycemicEvent> touched = scanUserEvents(user.getId());
                scanned++;
                if (touched.isEmpty()) skipped++; else flagged++;
            } catch (Exception e) {
                failed++;
                log.warn("Glycemic-event scan failed for user {}: {}", user.getId(), e.getMessage());
            }
        }
        ScanSummary summary = new ScanSummary(scanned, flagged, skipped, failed);
        log.info("Glycemic-event scan: scanned={}, flagged={}, skipped={}, failed={}", scanned, flagged, skipped, failed);
        return summary;
    }

    /** The strongest event touched by one scan (largest |mean residual|), for callers that want one. */
    @Transactional
    public Optional<GlycemicEvent> scanUser(UUID userId) {
        return scanUserEvents(userId).stream()
                .max(Comparator.comparingDouble(e -> Math.abs(e.getMeanResidualMmol())));
    }

    /** Scan one user's recent window; returns every event created or updated by this pass. */
    @Transactional
    public List<GlycemicEvent> scanUserEvents(UUID userId) {
        if (!featureToggleConfig.isGlycemicEventDetectionEnabled()) return List.of();

        // Run on the user's own clock: notes hold local wall time, CGM rows hold UTC epochs (audit F26).
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        ZoneId zone = resolveUserZone(settings);
        LocalDateTime end = LocalDateTime.now(zone);
        GlycemicEventDetector.Input in = loadInput(userId, settings, zone, end);

        List<Detection> detections = new ArrayList<>(detector.detect(in));
        detections.sort(Comparator.comparingLong(Detection::onsetMs));

        LocalDateTime windowStart = end.minusMinutes(props.getWindowMinutes());
        List<GlycemicEvent> open = new ArrayList<>(eventRepository.findByUserIdAndStateIn(userId, List.of(State.OPEN)));
        List<GlycemicEvent> answered = eventRepository.findByUserIdAndStateInAndWindowEndAfter(
                userId, List.of(State.CONFIRMED, State.DISMISSED), windowStart.minusHours(1));
        EventClassifier classifier = new EventClassifier(classifierConfig(in.iob()));

        List<GlycemicEvent> touched = new ArrayList<>();
        for (Detection d : detections) {
            LocalDateTime onset = toLdt(d.onsetMs(), zone), wEnd = toLdt(d.run().endMs(), zone);
            // The user already answered this move - never re-open it as a fresh marker.
            if (answered.stream().anyMatch(a -> a.getDirection() == d.direction() && overlaps(a, onset, wEnd))) continue;
            EventClassifier.Classification c = classifier.classify(d.direction(), d.features());
            double confidence = EventClassifier.confidence(c.baseConfidence(), d.features().sigmaMultiple());
            touched.add(persist(userId, d, c, confidence, false, c.reason(), open, zone));
        }
        return touched;
    }

    GlycemicEventDetector.Input loadInput(UUID userId, UserSettingsDTO settings, ZoneId zone, LocalDateTime end) {
        LocalDateTime start = end.minusMinutes(props.getWindowMinutes());
        List<CgmReading> readings = cgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                userId, UserZones.toEpochMs(start, zone), UserZones.toEpochMs(end, zone));
        List<GlycemicEventDetector.CgmPoint> cgm = readings.stream()
                .filter(r -> r.getSgv() != null && r.getDateTimestamp() != null)
                .map(r -> new GlycemicEventDetector.CgmPoint(r.getDateTimestamp(), r.getSgv() / MGDL_PER_MMOL))
                .toList();
        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(userId, start.minusHours(WARMUP_LOOKBACK_HOURS), end);
        List<CarbsEntry> carbEntries = notes.stream()
                .filter(n -> !n.isLongActing() && n.getCarbs() != null && n.getCarbs() > 0)
                .map(GlycemicEventDetector::toCarbsEntry).toList();
        return new GlycemicEventDetector.Input(userId, zone, end, cgm, notes, paramService.buildRawForUser(userId),
                insulinPrefsService.getRapidIobParameters(userId), settings,
                t -> carbsOnBoardService.calculateTotalCarbsOnBoard(carbEntries, t, settings),
                featureToggleConfig.isActivityLoggingEnabled());
    }

    EventClassifier.Config classifierConfig(RapidInsulinIobParameters iob) {
        return new EventClassifier.Config(props.getSignificantIobDropMmol(), props.getCobZeroGrams(),
                props.getDawnStartHour(), props.getDawnEndHour(), props.getDawnMaxSlope(),
                (int) Math.round(iob.diaHours() * 60), props.getDropResolveMinutes());
    }

    /**
     * Create an event, or update the overlapping OPEN event of the same direction. An existing event keeps
     * its category and phase unless the new classification comes from something the user logged
     * (rules R1/R2/F1/F3) - a later log is the one thing that should change the engine's mind.
     */
    GlycemicEvent persist(UUID userId, Detection d, EventClassifier.Classification c, double confidence,
                          boolean suppressed, String reason, List<GlycemicEvent> open, ZoneId zone) {
        LocalDateTime onset = toLdt(d.onsetMs(), zone);
        LocalDateTime wStart = toLdt(d.run().startMs(), zone);
        LocalDateTime wEnd = toLdt(d.run().endMs(), zone);
        Optional<GlycemicEvent> existing = open.stream()
                .filter(e -> e.getDirection() == d.direction() && e.getLinkedEventId() == null && overlaps(e, onset, wEnd))
                .findFirst();
        boolean isNew = existing.isEmpty();
        GlycemicEvent e = existing.orElseGet(() -> GlycemicEvent.builder().userId(userId).state(State.OPEN).build());
        Category before = e.getCategory();
        Phase phaseBefore = isNew ? null : e.getPhase();

        if (isNew || EventClassifier.isLoggedContextRule(c.reason())) {
            e.setCategory(c.category());
            e.setDetectorCategory(c.category());
            e.setPhase(c.phase());
            e.setResolvesAt(c.resolveAfterMinutes() == null ? null : onset.plusMinutes(c.resolveAfterMinutes()));
        }
        e.setDirection(d.direction());
        e.setOnsetAt(isNew || onset.isBefore(e.getOnsetAt()) ? onset : e.getOnsetAt());
        e.setWindowStart(isNew || wStart.isBefore(e.getWindowStart()) ? wStart : e.getWindowStart());
        e.setWindowEnd(isNew || wEnd.isAfter(e.getWindowEnd()) ? wEnd : e.getWindowEnd());
        e.setMeanResidualMmol(round2(d.run().mean()));
        e.setSigmaMultiple(round2(d.features().sigmaMultiple()));
        if (e.getFeatures() == null || e.getFeatures().insulinEffectRatio() == null) e.setFeatures(d.features());
        e.setConfidence(round2(confidence));
        e.setSuppressed(suppressed);
        e.setUpdatedAt(LocalDateTime.now());

        GlycemicEvent saved = eventRepository.save(e);
        if (isNew) {
            open.add(saved);
            historyRecorder.record(saved, Kind.DETECTED, null, Actor.SYSTEM, reason);
        } else if (before != saved.getCategory() || phaseBefore != saved.getPhase()) {
            historyRecorder.record(saved, Kind.UPDATED, before, Actor.SYSTEM, reason);
        }
        return saved;
    }

    static boolean overlaps(GlycemicEvent e, LocalDateTime start, LocalDateTime end) {
        LocalDateTime from = e.getOnsetAt() != null && e.getOnsetAt().isBefore(e.getWindowStart())
                ? e.getOnsetAt() : e.getWindowStart();
        return !from.isAfter(end) && !start.isAfter(e.getWindowEnd());
    }

    private static ZoneId resolveUserZone(UserSettingsDTO settings) {
        if (settings == null) return ZoneOffset.UTC;
        return UserZones.resolve(settings.getTimezone(), settings.getUtcOffsetMinutes());
    }

    private static LocalDateTime toLdt(long epochMs, ZoneId zone) { return UserZones.toWallTime(epochMs, zone); }

    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
