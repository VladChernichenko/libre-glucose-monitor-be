package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.VerificationEventDTO;
import che.glucosemonitorbe.dto.VerificationSummaryDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.UserSettings;
import che.glucosemonitorbe.entity.VerificationEvent;
import che.glucosemonitorbe.entity.VerificationSummary;
import che.glucosemonitorbe.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class VerificationService {

    // Rolling window size
    private static final int WINDOW_SIZE = 7;
    // Thresholds for triggering a suggestion
    private static final double MEAN_ERROR_THRESHOLD_CR  = 0.5;   // mmol/L
    private static final double CONSISTENCY_THRESHOLD    = 0.60;  // 0–1
    // Below this mean predicted rise the error can't be attributed to the rise coefficient reliably,
    // so the relative scale would explode — suppress the suggestion instead.
    private static final double MIN_PREDICTED_RISE       = 0.5;   // mmol/L

    // Qualifying meal range
    private static final double MIN_CARBS = 20.0;
    private static final double MAX_CARBS = 80.0;

    // Max distance between a target time (baseline / +2h) and the CGM reading matched to it
    private static final long CGM_MATCH_TOLERANCE_MS = 20 * 60 * 1000L;  // ±20 minutes

    private final VerificationEventRepository verificationEventRepository;
    private final VerificationSummaryRepository verificationSummaryRepository;
    private final NoteRepository noteRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final CgmReadingRepository cgmReadingRepository;

    // ── Enqueue a note for verification ──────────────────────────────────────

    public void enqueueNote(UUID noteId, UUID userId) {
        // Skip if already enqueued
        if (verificationEventRepository.findByNoteId(noteId).isPresent()) return;

        Note note = noteRepository.findById(noteId).orElse(null);
        if (note == null) return;

        // Fast eligibility pre-check (carbs in range, insulin present)
        String skipReason = preCheckEligibility(note);
        VerificationEvent.Status initialStatus = skipReason == null
                ? VerificationEvent.Status.PENDING
                : VerificationEvent.Status.SKIPPED;

        VerificationEvent event = VerificationEvent.builder()
                .userId(userId)
                .noteId(noteId)
                .status(initialStatus)
                .skipReason(skipReason)
                .build();

        verificationEventRepository.save(event);
    }

    // ── Evaluate pending events (called by scheduler) ─────────────────────────

    public void evaluatePending() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        List<VerificationEvent> pending = verificationEventRepository.findPendingReadyToEvaluate(cutoff);

        for (VerificationEvent event : pending) {
            try {
                evaluateEvent(event);
            } catch (Exception e) {
                log.warn("Failed to evaluate verification event {}: {}", event.getId(), e.getMessage());
            }
        }
    }

    private void evaluateEvent(VerificationEvent event) {
        Note note = noteRepository.findById(event.getNoteId()).orElse(null);
        if (note == null) {
            event.setStatus(VerificationEvent.Status.SKIPPED);
            event.setSkipReason("note_not_found");
            verificationEventRepository.save(event);
            return;
        }

        // Full eligibility check (time-sensitive checks)
        String skipReason = fullEligibilityCheck(note, event.getUserId());
        if (skipReason != null) {
            event.setStatus(VerificationEvent.Status.SKIPPED);
            event.setSkipReason(skipReason);
            event.setEvaluatedAt(LocalDateTime.now());
            verificationEventRepository.save(event);
            return;
        }

        // Get CGM baseline (closest reading to note timestamp)
        Long baselineTs = toEpochMs(note.getTimestamp());
        Long twoHourTs  = toEpochMs(note.getTimestamp().plusHours(2));

        Double baseline = findClosestCgm(event.getUserId(), baselineTs);
        Double twoHour  = findClosestCgm(event.getUserId(), twoHourTs);

        if (baseline == null || twoHour == null) {
            event.setStatus(VerificationEvent.Status.SKIPPED);
            event.setSkipReason("cgm_data_unavailable");
            event.setEvaluatedAt(LocalDateTime.now());
            verificationEventRepository.save(event);
            return;
        }

        // Compute predicted vs actual
        UserSettings cob = userSettingsRepository.findByUserId(event.getUserId()).orElse(null);
        double carbRatio = cob != null && cob.getCarbRatio() != null ? cob.getCarbRatio() : 2.0;
        double isf       = cob != null && cob.getIsf()       != null ? cob.getIsf()       : 1.0;

        double carbs   = note.getCarbs()   != null ? note.getCarbs()   : 0.0;
        double insulin = note.getInsulin() != null ? note.getInsulin() : 0.0;

        // carbRatio in DB = mmol/L rise per 10g carbs → convert to per gram
        double carbRatioPerGram = carbRatio / 10.0;
        double predictedDelta = (carbs * carbRatioPerGram) - (insulin * isf);
        double actualDelta    = twoHour - baseline;
        double error          = actualDelta - predictedDelta;
        double relError       = Math.abs(predictedDelta) > 0.01
                ? (error / Math.abs(predictedDelta)) * 100.0 : 0.0;

        event.setStatus(VerificationEvent.Status.COMPLETED);
        event.setBaselineGlucose(round2(baseline));
        event.setActualGlucose2h(round2(twoHour));
        event.setPredictedDelta(round2(predictedDelta));
        event.setActualDelta(round2(actualDelta));
        event.setError(round2(error));
        event.setRelativeErrorPct(round2(relError));
        event.setEvaluatedAt(LocalDateTime.now());
        verificationEventRepository.save(event);

        // Refresh rolling summary
        refreshSummary(event.getUserId(), cob);
    }

    // ── Rolling summary ───────────────────────────────────────────────────────

    private void refreshSummary(UUID userId, UserSettings cob) {
        List<VerificationEvent> completed = verificationEventRepository.findCompletedByUserId(userId);
        List<VerificationEvent> window = completed.stream().limit(WINDOW_SIZE).toList();

        VerificationSummary summary = verificationSummaryRepository.findById(userId)
                .orElse(VerificationSummary.builder().userId(userId).build());

        summary.setNEvents(window.size());
        summary.setLastUpdated(LocalDateTime.now());

        if (window.size() < 2) {
            summary.setSuggestionReady(false);
            verificationSummaryRepository.save(summary);
            return;
        }

        List<Double> errors = window.stream()
                .map(VerificationEvent::getError).filter(Objects::nonNull).collect(Collectors.toList());
        double meanError = errors.stream().mapToDouble(d -> d).average().orElse(0);
        double stddev    = stddev(errors, meanError);
        double consistency = Math.abs(meanError) > 0.01
                ? 1.0 - (stddev / Math.abs(meanError)) : 0.0;
        consistency = Math.max(0.0, Math.min(1.0, consistency));

        summary.setMeanError(round2(meanError));
        summary.setConsistencyScore(round2(consistency));

        // Recompute from scratch each pass; clear any stale suggestion first.
        summary.setSuggestedCarbRatio(null);
        summary.setSuggestedIsf(null);

        // Attribute a *consistent* systematic error to a single knob. Every qualifying event is a
        // meal with a bolus (carbs 20–80 g, insulin > 0), so the 2 h net error cannot be split
        // between the carb-rise coefficient and the model ISF — adjusting both would double-count
        // the same miss. We therefore correct only the carb ratio (the dominant driver of the
        // post-meal excursion), scaled *relative* to the mean predicted rise rather than by the
        // absolute mmol error (a fixed absolute step over/under-corrects small/large meals alike).
        double meanAbsPredicted = window.stream()
                .map(VerificationEvent::getPredictedDelta).filter(Objects::nonNull)
                .mapToDouble(Math::abs).average().orElse(0.0);

        boolean ready = false;
        if (consistency >= CONSISTENCY_THRESHOLD
                && Math.abs(meanError) >= MEAN_ERROR_THRESHOLD_CR
                && meanAbsPredicted >= MIN_PREDICTED_RISE
                && cob != null) {
            double curCR = cob.getCarbRatio() != null ? cob.getCarbRatio() : 2.0;
            // meanError > 0 → actual exceeded prediction → predicted rise too low → raise CR;
            // meanError < 0 → predicted too high → lower CR. relError carries both signs.
            double relError = meanError / meanAbsPredicted;
            double scale = Math.max(0.5, Math.min(2.0, 1.0 + relError));
            summary.setSuggestedCarbRatio(round2(curCR * scale));
            ready = window.size() >= WINDOW_SIZE;
        }
        summary.setSuggestionReady(ready && window.size() >= WINDOW_SIZE);
        verificationSummaryRepository.save(summary);
    }

    // ── Public queries ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public VerificationSummaryDTO getSummary(UUID userId) {
        VerificationSummary s = verificationSummaryRepository.findById(userId)
                .orElse(VerificationSummary.builder().userId(userId).build());
        return toSummaryDTO(s);
    }

    public void acceptSuggestion(UUID userId) {
        VerificationSummary summary = verificationSummaryRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("No verification summary for user " + userId));

        UserSettings cob = userSettingsRepository.findByUserId(userId).orElseThrow();
        if (summary.getSuggestedCarbRatio() != null) cob.setCarbRatio(summary.getSuggestedCarbRatio());
        if (summary.getSuggestedIsf()       != null) cob.setIsf(summary.getSuggestedIsf());
        userSettingsRepository.save(cob);

        // Reset rolling window by marking completed events as stale (re-use skip status)
        List<VerificationEvent> completed = verificationEventRepository.findCompletedByUserId(userId);
        for (VerificationEvent e : completed) {
            e.setStatus(VerificationEvent.Status.SKIPPED);
            e.setSkipReason("window_reset_after_suggestion_accepted");
        }
        verificationEventRepository.saveAll(completed);

        summary.setNEvents(0);
        summary.setSuggestionReady(false);
        summary.setSuggestedCarbRatio(null);
        summary.setSuggestedIsf(null);
        summary.setLastUpdated(LocalDateTime.now());
        verificationSummaryRepository.save(summary);
    }

    @Transactional(readOnly = true)
    public List<VerificationEventDTO> getEvents(UUID userId) {
        return verificationEventRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toEventDTO).toList();
    }

    // ── Eligibility ───────────────────────────────────────────────────────────

    private String preCheckEligibility(Note note) {
        double carbs   = note.getCarbs()   != null ? note.getCarbs()   : 0.0;
        double insulin = note.getInsulin() != null ? note.getInsulin() : 0.0;
        if (carbs < MIN_CARBS || carbs > MAX_CARBS) return "carbs_out_of_range";
        if (insulin <= 0) return "no_insulin";
        // Exclude complex meals (HFHP)
        String profile = note.getNutritionProfile();
        if (profile != null && (profile.contains("HFHP") || profile.contains("Double Wave") ||
                profile.contains("\"fat\":") && extractFat(profile) > 20)) {
            return "complex_meal";
        }
        return null;
    }

    private String fullEligibilityCheck(Note note, UUID userId) {
        String pre = preCheckEligibility(note);
        if (pre != null) return pre;
        // Stacking check: any other insulin notes in the 3 hours prior?
        LocalDateTime windowStart = note.getTimestamp().minusHours(3);
        List<Note> prior = noteRepository.findByUserIdAndTimestampBetween(userId, windowStart, note.getTimestamp());
        boolean stacked = prior.stream().anyMatch(n -> !n.getId().equals(note.getId())
                && n.getInsulin() != null && n.getInsulin() > 0);
        if (stacked) return "insulin_stacking";
        return null;
    }

    private double extractFat(String json) {
        try {
            int idx = json.indexOf("\"fat\":");
            if (idx < 0) return 0;
            String sub = json.substring(idx + 6).stripLeading();
            int end = sub.indexOf(',');
            if (end < 0) end = sub.indexOf('}');
            if (end < 0) return 0;
            return Double.parseDouble(sub.substring(0, end).strip());
        } catch (Exception e) { return 0; }
    }

    // ── CGM helper ────────────────────────────────────────────────────────────

    private Double findClosestCgm(UUID userId, Long targetMs) {
        // Fetch only the small window around the target instead of scanning history: an
        // OrderByDateTimestampAsc page-0 query returns the OLDEST readings, so for any user with
        // more than a page of history the recent baseline/+2h target was never in range and every
        // event was wrongly skipped as cgm_data_unavailable.
        List<CgmReading> readings = cgmReadingRepository
                .findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                        userId, targetMs - CGM_MATCH_TOLERANCE_MS, targetMs + CGM_MATCH_TOLERANCE_MS);
        CgmReading closest = null;
        long minDiff = Long.MAX_VALUE;
        for (CgmReading r : readings) {
            if (r.getDateTimestamp() == null) continue;
            long diff = Math.abs(r.getDateTimestamp() - targetMs);
            if (diff < minDiff) {  // window already bounds to within tolerance
                minDiff = diff;
                closest = r;
            }
        }
        if (closest == null || closest.getSgv() == null) return null;
        // sgv is mg/dL — convert to mmol/L
        return round2(closest.getSgv() / 18.0);
    }

    private Long toEpochMs(LocalDateTime ldt) {
        return ldt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    private VerificationSummaryDTO toSummaryDTO(VerificationSummary s) {
        String confidence = computeConfidence(s);
        return VerificationSummaryDTO.builder()
                .nEvents(s.getNEvents())
                .meanError(s.getMeanError())
                .consistencyScore(s.getConsistencyScore())
                .suggestedIsf(s.getSuggestedIsf())
                .suggestedCarbRatio(s.getSuggestedCarbRatio())
                .suggestionReady(Boolean.TRUE.equals(s.getSuggestionReady()))
                .confidence(confidence)
                .lastUpdated(s.getLastUpdated())
                .build();
    }

    private String computeConfidence(VerificationSummary s) {
        int n = s.getNEvents();
        double cs = s.getConsistencyScore() != null ? s.getConsistencyScore() : 0.0;
        if (n >= WINDOW_SIZE && cs >= 0.75) return "HIGH";
        if (n >= 4          && cs >= 0.50) return "MEDIUM";
        return "LOW";
    }

    private VerificationEventDTO toEventDTO(VerificationEvent e) {
        return VerificationEventDTO.builder()
                .id(e.getId())
                .noteId(e.getNoteId())
                .status(e.getStatus())
                .baselineGlucose(e.getBaselineGlucose())
                .actualGlucose2h(e.getActualGlucose2h())
                .predictedDelta(e.getPredictedDelta())
                .actualDelta(e.getActualDelta())
                .error(e.getError())
                .relativeErrorPct(e.getRelativeErrorPct())
                .skipReason(e.getSkipReason())
                .evaluatedAt(e.getEvaluatedAt())
                .createdAt(e.getCreatedAt())
                .build();
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    private double stddev(List<Double> values, double mean) {
        if (values.size() < 2) return 0;
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        return Math.sqrt(variance);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
