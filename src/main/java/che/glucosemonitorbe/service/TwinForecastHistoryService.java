package che.glucosemonitorbe.service;

import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshot;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotRepository;
import che.glucosemonitorbe.assistant.snapshot.SnapshotForecaster;
import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.AnchorForecastDTO;
import che.glucosemonitorbe.dto.ForecastAnchorDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.empirical.EmpiricalEvent;
import che.glucosemonitorbe.empirical.EmpiricalEventMapper;
import che.glucosemonitorbe.empirical.EmpiricalForecastCone;
import che.glucosemonitorbe.empirical.EmpiricalTwinEngine;
import che.glucosemonitorbe.empirical.EmpiricalTwinParams;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.exception.ResourceNotFoundException;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine.Reading;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Forecast at each log": the forecast frozen when each dose, meal or fast sugar was logged
 * ({@link ForecastSnapshot}), beside what the CGM actually did, so the user can step through their
 * history. A note without a snapshot (older, backdated, or excluded after a late edit) is not an
 * anchor. The frozen points carry no cone: lower and upper equal the forecast.
 *
 * <p>The engine recompute of spec F2.3 ({@code recomputedAnchors} / {@code recomputedForecast}) stays
 * for tests and backtests only. Notes hold local wall time and CGM readings true UTC, so readings
 * are moved onto the user's wall clock before use.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TwinForecastHistoryService {

    /** Longest history the anchor list covers [days]. */
    public static final int MAX_DAYS = 90;
    /** Notes this far before an anchor can still act on its forecast (slow fatty-meal tail) [h]. */
    private static final int EVENT_LOOKBACK_HOURS = 8;
    /** Chart context shown before the anchor [min]. */
    private static final int CONTEXT_BEFORE_MIN = 60;
    /** Horizons compared with the CGM [min]. */
    private static final int[] CHECK_MINUTES = {60, 120, 240};
    /** Frozen forecasts have no likely-range cone. */
    private static final double NO_CONE_COVERAGE = 0.0;
    private static final EmpiricalForecastCone CONE = EmpiricalForecastCone.defaults();

    private final NoteRepository noteRepository;
    private final CgmReadingRepository cgmReadingRepository;
    private final UserSettingsService userSettingsService;
    private final NoteToCarbsEntryMapper noteToCarbsEntryMapper;
    private final FeatureToggleConfig featureToggleConfig;
    private final ForecastSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    /** Notes with a live snapshot, logged in the last {@code days} days, newest first. */
    public List<ForecastAnchorDTO> anchors(UUID userId, int days) {
        int d = Math.max(1, Math.min(MAX_DAYS, days));
        LocalDateTime now = LocalDateTime.now(zone(userId));
        List<Note> inWindow = noteRepository.findByUserIdAndTimestampBetween(userId, now.minusDays(d), now);
        if (inWindow.isEmpty()) return List.of();
        List<UUID> withSnapshot = snapshotRepository
                .findByNoteIdInAndExcludedFalse(inWindow.stream().map(Note::getId).toList()).stream()
                .filter(s -> userId.equals(s.getUserId()))
                .map(ForecastSnapshot::getNoteId)
                .toList();
        return inWindow.stream()
                .filter(n -> n.getTimestamp() != null && withSnapshot.contains(n.getId()))
                .sorted(Comparator.comparing(Note::getTimestamp).reversed())
                .map(TwinForecastHistoryService::toAnchor)
                .toList();
    }

    /** The frozen forecast of one of the user's notes; 404 when it has no live snapshot. */
    public AnchorForecastDTO forecast(UUID userId, UUID noteId) {
        ZoneId zone = zone(userId);
        LocalDateTime now = LocalDateTime.now(zone);
        Note anchor = noteRepository.findByIdAndUserId(noteId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("No forecast anchor " + noteId));
        ForecastSnapshot snap = snapshotRepository.findByNoteId(noteId)
                .filter(s -> userId.equals(s.getUserId()) && !s.isExcluded())
                .orElseThrow(() -> new ResourceNotFoundException("No forecast anchor " + noteId));
        LocalDateTime t0 = anchor.getTimestamp();
        LocalDateTime chartEnd = t0.plusMinutes(EmpiricalTwinEngine.HORIZON_MIN);
        LocalDateTime savedAt = LocalDateTime.ofInstant(snap.getMadeAt(), zone);

        List<SnapshotForecaster.Point> frozen = points(snap);
        List<AnchorForecastDTO.Point> forecast = frozen.stream()
                .map(p -> new AnchorForecastDTO.Point(p.minute(), savedAt.plusMinutes(p.minute()),
                        round1(p.mmol()), round1(p.mmol()), round1(p.mmol())))
                .toList();

        Map<Integer, Double> predicted = frozen.stream()
                .collect(Collectors.toMap(SnapshotForecaster.Point::minute, SnapshotForecaster.Point::mmol, (x, y) -> x));
        List<AnchorForecastDTO.Check> checks = List.of(
                check(60, predicted, snap.getCheck60Actual(), snap.getCheck60Hit()),
                check(120, predicted, snap.getCheck120Actual(), snap.getCheck120Hit()),
                check(240, predicted, snap.getCheck240Actual(), snap.getCheck240Hit()));

        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(
                userId, t0.minusHours(EVENT_LOOKBACK_HOURS), chartEnd);
        return new AnchorForecastDTO(
                toAnchor(anchor),
                snap.getStartGlucose() != null ? round1(snap.getStartGlucose()) : null,
                forecast,
                cgmReadings(userId, t0, chartEnd, zone).stream()
                        .map(r -> new AnchorForecastDTO.Reading(wallTime(r.epochMs()), round1(r.mmol()))).toList(),
                markers(notes, t0.minusMinutes(CONTEXT_BEFORE_MIN)),
                checks,
                NO_CONE_COVERAGE,
                chartEnd.isAfter(now));
    }

    private static AnchorForecastDTO.Check check(int minute, Map<Integer, Double> predicted, Double actual, Boolean hit) {
        Double p = predicted.get(minute);
        return new AnchorForecastDTO.Check(minute, p != null ? round1(p) : null,
                actual != null ? round1(actual) : null, hit);
    }

    private List<SnapshotForecaster.Point> points(ForecastSnapshot snap) {
        try {
            return objectMapper.readValue(snap.getPoints(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable forecast snapshot " + snap.getId(), e);
        }
    }

    private List<Reading> cgmReadings(UUID userId, LocalDateTime t0, LocalDateTime chartEnd, ZoneId zone) {
        return cgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                        userId,
                        UserZones.toEpochMs(t0.minusMinutes(CONTEXT_BEFORE_MIN), zone),
                        UserZones.toEpochMs(chartEnd, zone))
                .stream()
                .filter(r -> r.getSgv() != null && r.getDateTimestamp() != null)
                .map(r -> toWallReading(r, zone))
                .toList();
    }

    /** Engine recompute with today's parameters, oldest first. Tests and backtests only. */
    List<ForecastAnchorDTO> recomputedAnchors(UUID userId, int days) {
        int d = Math.max(1, Math.min(MAX_DAYS, days));
        LocalDateTime now = LocalDateTime.now(zone(userId));
        return noteRepository.findByUserIdAndTimestampBetween(userId, now.minusDays(d), now).stream()
                .filter(TwinForecastHistoryService::isAnchor)
                .sorted(Comparator.comparing(Note::getTimestamp))
                .map(TwinForecastHistoryService::toAnchor)
                .toList();
    }

    /** Engine recompute for one anchor note; 404 for someone else's note or a non-anchor. Tests and backtests only. */
    AnchorForecastDTO recomputedForecast(UUID userId, UUID noteId, ZoneId zone, LocalDateTime now) {
        Note anchor = noteRepository.findByIdAndUserId(noteId, userId)
                .filter(TwinForecastHistoryService::isAnchor)
                .orElseThrow(() -> new ResourceNotFoundException("No forecast anchor " + noteId));
        LocalDateTime t0 = anchor.getTimestamp();
        LocalDateTime chartEnd = t0.plusMinutes(EmpiricalTwinEngine.HORIZON_MIN);

        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(
                userId, t0.minusHours(EVENT_LOOKBACK_HOURS), chartEnd);
        List<Reading> cgm = cgmReadings(userId, t0, chartEnd, zone);

        long anchorMs = EmpiricalEventMapper.epochMs(t0);
        List<EmpiricalTwinEngine.Point> path =
                EmpiricalTwinEngine.forecast(anchorMs, cgm, events(notes), EmpiricalTwinParams.defaults());

        List<AnchorForecastDTO.Point> forecast = path.stream().map(p -> {
            double half = CONE.halfWidthMmol(p.minute());
            return new AnchorForecastDTO.Point(p.minute(), t0.plusMinutes(p.minute()), round1(p.mmol()),
                    round1(Math.max(0, p.mmol() - half)), round1(p.mmol() + half));
        }).toList();

        List<AnchorForecastDTO.Check> checks = new ArrayList<>();
        for (int minute : CHECK_MINUTES) {
            Double predicted = path.stream().filter(p -> p.minute() == minute)
                    .map(EmpiricalTwinEngine.Point::mmol).findFirst().orElse(null);
            Double actual = EmpiricalTwinEngine.actualAt(cgm, anchorMs + minute * 60_000L);
            Boolean hit = predicted != null && actual != null ? EmpiricalTwinEngine.isHit(predicted, actual) : null;
            checks.add(new AnchorForecastDTO.Check(minute,
                    predicted != null ? round1(predicted) : null, actual != null ? round1(actual) : null, hit));
        }

        return new AnchorForecastDTO(
                toAnchor(anchor),
                path.isEmpty() ? null : round1(path.get(0).mmol()),
                forecast,
                cgm.stream().map(r -> new AnchorForecastDTO.Reading(wallTime(r.epochMs()), round1(r.mmol()))).toList(),
                markers(notes, t0.minusMinutes(CONTEXT_BEFORE_MIN)),
                checks,
                EmpiricalForecastCone.COVERAGE,
                chartEnd.isAfter(now));
    }

    /** A rapid dose, a meal or fast sugar; basal and activity notes are context, not anchors. */
    static boolean isAnchor(Note n) {
        if (n.getTimestamp() == null || n.isLongActing() || n.isActivity()) return false;
        return positive(n.getInsulin()) || positive(n.getCarbs());
    }

    private List<EmpiricalEvent> events(List<Note> notes) {
        List<EmpiricalEvent> events = new ArrayList<>();
        for (Note n : notes) {
            if (n.getTimestamp() == null || n.isLongActing()) continue;
            if (n.isActivity()) {
                if (featureToggleConfig.isActivityLoggingEnabled()) EmpiricalEventMapper.walk(n).ifPresent(events::add);
                continue;
            }
            if (positive(n.getCarbs())) {
                events.add(EmpiricalEventMapper.carbs(noteToCarbsEntryMapper.toCarbsEntry(n)));
            }
            if (positive(n.getInsulin())) {
                events.add(EmpiricalEventMapper.insulin(InsulinDose.builder()
                        .id(n.getId()).timestamp(n.getTimestamp()).units(n.getInsulin()).userId(n.getUserId()).build()));
            }
        }
        return events;
    }

    private static List<AnchorForecastDTO.Marker> markers(List<Note> notes, LocalDateTime from) {
        List<AnchorForecastDTO.Marker> out = new ArrayList<>();
        for (Note n : notes) {
            LocalDateTime t = n.getTimestamp();
            if (t == null || t.isBefore(from)) continue;
            if (n.isActivity()) {
                if (n.getDurationMin() != null) out.add(new AnchorForecastDTO.Marker(t, "ACTIVITY", n.getDurationMin()));
                continue;
            }
            if (positive(n.getInsulin())) {
                out.add(new AnchorForecastDTO.Marker(t, n.isLongActing() ? "BASAL" : "INSULIN", n.getInsulin()));
            }
            if (positive(n.getCarbs())) out.add(new AnchorForecastDTO.Marker(t, "CARBS", n.getCarbs()));
        }
        out.sort(Comparator.comparing(AnchorForecastDTO.Marker::time));
        return out;
    }

    private static ForecastAnchorDTO toAnchor(Note n) {
        return new ForecastAnchorDTO(n.getId(), n.getTimestamp(), label(n),
                positive(n.getCarbs()) ? n.getCarbs() : null,
                positive(n.getInsulin()) ? n.getInsulin() : null);
    }

    private static String label(Note n) {
        if (n.getComment() != null && !n.getComment().isBlank()) return n.getComment().strip();
        if (n.getDetailedInput() != null && !n.getDetailedInput().isBlank()) return n.getDetailedInput().strip();
        return n.getMeal();
    }

    /** A CGM reading moved onto the user's wall clock, as the engine expects. */
    private static Reading toWallReading(CgmReading r, ZoneId zone) {
        long wallMs = EmpiricalEventMapper.epochMs(UserZones.toWallTime(r.getDateTimestamp(), zone));
        return new Reading(wallMs, r.getSgv() / GlucoseConversion.MGDL_PER_MMOL);
    }

    private static LocalDateTime wallTime(long wallEpochMs) {
        return UserZones.toWallTime(wallEpochMs, java.time.ZoneOffset.UTC);
    }

    private ZoneId zone(UUID userId) {
        UserSettingsDTO s = userSettingsService.getUserSettings(userId);
        return s == null ? java.time.ZoneOffset.UTC : UserZones.resolve(s.getTimezone(), s.getUtcOffsetMinutes());
    }

    private static boolean positive(Double v) {
        return v != null && v > 0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
