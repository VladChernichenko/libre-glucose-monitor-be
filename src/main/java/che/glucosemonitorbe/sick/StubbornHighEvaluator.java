package che.glucosemonitorbe.sick;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a correction dose in a sick episode failed to bring a high glucose down, in which
 * case the app proposes the next severity level. Read-only: it never writes; the user's answer is
 * recorded by {@link SickModeService#decideStepUp}.
 */
@Component
@RequiredArgsConstructor
public class StubbornHighEvaluator {

    static final int MIN_MINUTES = 120;
    static final double HIGH_MMOL = 13.0;
    static final double MIN_DROP_MMOL = 1.0;
    static final int CGM_MATCH_MINUTES = 15;

    private final NoteRepository notes;
    private final CgmReadingRepository cgmReadings;
    private final SickStepUpDecisionRepository decisions;
    private final UserSettingsService userSettingsService;

    /**
     * The proposal for the episode's newest correction, or empty unless: it was logged at least
     * {@link #MIN_MINUTES} ago, glucose now is above {@link #HIGH_MMOL} and fell by less than
     * {@link #MIN_DROP_MMOL} since the correction, no carbs were logged after it, it has no recorded
     * decision, and a reading at the correction is known.
     */
    public Optional<SickModeStatusDTO.StepUp> evaluate(UUID userId, SickSegment open, LocalDateTime episodeStart,
                                                       LocalDateTime now, double readingNow) {
        if (open == null || !(readingNow > HIGH_MMOL)) {
            return Optional.empty();
        }
        List<Note> episodeNotes = notes.findByUserIdAndTimestampBetween(userId, episodeStart, now);
        Optional<Note> found = newestCorrection(episodeNotes, episodeStart, now);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Note correction = found.get();
        if (Duration.between(correction.getTimestamp(), now).toMinutes() < MIN_MINUTES) {
            return Optional.empty();
        }
        if (decisions.existsByUserIdAndNoteId(userId, correction.getId())) {
            return Optional.empty();
        }
        boolean carbsAfter = episodeNotes.stream().anyMatch(n -> n.getCarbs() != null && n.getCarbs() > 0
                && n.getTimestamp().isAfter(correction.getTimestamp()));
        if (carbsAfter) {
            return Optional.empty();
        }
        Double atCorrection = glucoseAt(userId, correction);
        if (atCorrection == null || !(readingNow > atCorrection - MIN_DROP_MMOL)) {
            return Optional.empty();
        }
        SickSeverity severity = open.getSeverity();
        return Optional.of(new SickModeStatusDTO.StepUp(correction.getId(), severity.next().orElse(null),
                severity == SickSeverity.SEVERE || severity == SickSeverity.SEVERE_UNRESPONSIVE));
    }

    private static Optional<Note> newestCorrection(List<Note> episodeNotes, LocalDateTime episodeStart,
                                                   LocalDateTime now) {
        return episodeNotes.stream()
                .filter(Note::isCorrectionBolus)
                .filter(n -> n.getTimestamp() != null && !n.getTimestamp().isBefore(episodeStart)
                        && !n.getTimestamp().isAfter(now))
                .max(Comparator.comparing(Note::getTimestamp));
    }

    /** The glucose logged with the correction, else the CGM reading nearest it within the match window. */
    private Double glucoseAt(UUID userId, Note correction) {
        if (correction.getGlucoseLevel() != null) {
            return correction.getGlucoseLevel();
        }
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        ZoneId zone = UserZones.resolve(settings.getTimezone(), settings.getUtcOffsetMinutes());
        long target = UserZones.toEpochMs(correction.getTimestamp(), zone);
        long window = CGM_MATCH_MINUTES * 60_000L;
        return cgmReadings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                        userId, target - window, target + window).stream()
                .filter(r -> r.getSgv() != null && r.getDateTimestamp() != null)
                .min(Comparator.comparingLong((CgmReading r) -> Math.abs(r.getDateTimestamp() - target)))
                .map(r -> r.getSgv() / GlucoseConversion.MGDL_PER_MMOL)
                .orElse(null);
    }
}
