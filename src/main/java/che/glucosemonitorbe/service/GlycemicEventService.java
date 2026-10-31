package che.glucosemonitorbe.service;

import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotTrigger;
import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.ConfirmGlycemicEventRequest;
import che.glucosemonitorbe.dto.GlycemicEventDTO;
import che.glucosemonitorbe.dto.GlycemicEventHistoryDTO;
import che.glucosemonitorbe.dto.GlycemicInsightsDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.GlycemicEventHistoryRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * User-facing operations on glycemic events (spec section 5): range listing for the chart, the single
 * prompt, confirm/relabel with optional backfill, dismiss, and the per-event history timeline. Every
 * user answer writes a history row, mirrors onto linked children, and refreshes the learner.
 */
@Service
@RequiredArgsConstructor
public class GlycemicEventService {

    private static final Set<Category> FOOD = EnumSet.of(Category.UNLOGGED_FOOD, Category.UNDER_ESTIMATED_FOOD);
    private static final Set<Category> INSULIN = EnumSet.of(Category.UNLOGGED_INSULIN, Category.UNDER_ESTIMATED_INSULIN);

    private final GlycemicEventRepository eventRepository;
    private final GlycemicEventHistoryRepository historyRepository;
    private final NoteRepository noteRepository;
    private final UserSettingsService userSettingsService;
    private final GlycemicEventProperties props;
    private final GlycemicEventHistoryRecorder historyRecorder;
    private final GlycemicLearnerTrainingCache trainingCache;
    private final ForecastSnapshotTrigger forecastSnapshotTrigger;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<GlycemicEventDTO> listRange(UUID userId, LocalDateTime from, LocalDateTime to, State state) {
        if (from == null || to == null || !from.isBefore(to) || to.isAfter(from.plusDays(31))) {
            throw unprocessable("from must be before to, and the range at most 31 days");
        }
        return eventRepository
                .findByUserIdAndOnsetAtLessThanEqualAndWindowEndGreaterThanEqualOrderByOnsetAtAsc(userId, to, from)
                .stream().filter(e -> state == null || e.getState() == state)
                .map(GlycemicEventDTO::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<GlycemicEventDTO> prompt(UUID userId) {
        LocalDateTime oldest = LocalDateTime.now(userZone(userId)).minusHours(props.getPromptMaxAgeHours());
        return eventRepository.findPromptCandidate(userId, props.getPromptMinConfidence(), oldest)
                .map(GlycemicEventDTO::from);
    }

    @Transactional
    public GlycemicEventDTO confirm(UUID userId, UUID eventId, ConfirmGlycemicEventRequest req) {
        GlycemicEvent event = requireOpen(userId, eventId);
        Category requested = parseCategory(req == null ? null : req.category());
        if (requested != null && requested.direction() != event.getDirection()) {
            throw unprocessable("Category " + requested + " does not fit a " + event.getDirection() + " event");
        }
        if (event.getPhase() == Phase.PROVISIONAL && requested == null) {
            throw unprocessable("Choose a category for a provisional event");
        }
        Category effective = requested != null ? requested : event.getCategory();
        double carbs = validateAmount(req == null ? null : req.carbs(), props.getBackfillMaxCarbs(), "carbs");
        double insulin = validateAmount(req == null ? null : req.insulin(), props.getBackfillMaxInsulin(), "insulin");
        if (carbs > 0 && !FOOD.contains(effective)) throw unprocessable("Carbs can only be added to a food event");
        if (insulin > 0 && !INSULIN.contains(effective)) throw unprocessable("Insulin can only be added to an insulin event");
        if (carbs > 0 || insulin > 0) backfill(userId, event.getOnsetAt(), carbs, insulin);

        Category before = event.getCategory();
        boolean relabel = requested != null && requested != before;
        if (relabel) {
            event.setUserLabel(requested);
            event.setCategory(requested);
        }
        resolve(event, State.CONFIRMED, LocalDateTime.now(userZone(userId)));
        GlycemicEvent saved = writeAnswer(event, relabel ? Kind.RELABELLED : Kind.CONFIRMED, before);
        trainingCache.invalidate(userId);
        return GlycemicEventDTO.from(saved);
    }

    @Transactional
    public GlycemicEventDTO dismiss(UUID userId, UUID eventId) {
        GlycemicEvent event = requireOpen(userId, eventId);
        Category before = event.getCategory();
        resolve(event, State.DISMISSED, LocalDateTime.now(userZone(userId)));
        GlycemicEvent saved = writeAnswer(event, Kind.DISMISSED, before);
        trainingCache.invalidate(userId);
        return GlycemicEventDTO.from(saved);
    }

    @Transactional(readOnly = true)
    public List<GlycemicEventHistoryDTO> history(UUID userId, UUID eventId) {
        eventRepository.findById(eventId).filter(e -> e.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        return historyRepository.findByEventIdOrderByAtAsc(eventId).stream().map(GlycemicEventHistoryDTO::from).toList();
    }

    @Transactional(readOnly = true)
    public GlycemicInsightsDTO insights(UUID userId, int days) {
        if (days < 1 || days > 90) throw unprocessable("days must be between 1 and 90");
        LocalDateTime now = LocalDateTime.now(userZone(userId));
        List<GlycemicEvent> all = eventRepository
                .findByUserIdAndOnsetAtLessThanEqualAndWindowEndGreaterThanEqualOrderByOnsetAtAsc(userId, now, now.minusDays(days))
                .stream().filter(e -> e.getLinkedEventId() == null).toList();

        Map<String, Integer> counts = new TreeMap<>();
        Map<String, List<Integer>> hours = new TreeMap<>();
        for (GlycemicEvent e : all) {
            if (e.getState() == State.DISMISSED) continue;
            String key = e.getCategory().name();
            counts.merge(key, 1, Integer::sum);
            List<Integer> h = hours.computeIfAbsent(key, k -> new ArrayList<>(Collections.nCopies(24, 0)));
            h.set(e.getOnsetAt().getHour(), h.get(e.getOnsetAt().getHour()) + 1);
        }
        int noEffect = (int) eventRepository.countLipodystrophyRecurrence(userId, now.minusDays(14));

        Map<String, Double> agreement = new TreeMap<>();
        Map<Category, List<GlycemicEvent>> answeredByDetector = all.stream()
                .filter(e -> e.getState() != State.OPEN && e.getDetectorCategory() != null)
                .collect(Collectors.groupingBy(GlycemicEvent::getDetectorCategory));
        answeredByDetector.forEach((cat, list) -> {
            long agreed = list.stream().filter(e -> e.getState() == State.CONFIRMED && e.getUserLabel() == null).count();
            agreement.put(cat.name(), Math.round(1000.0 * agreed / list.size()) / 1000.0);
        });
        return new GlycemicInsightsDTO(days, counts, hours, noEffect, agreement);
    }

    GlycemicEvent requireOpen(UUID userId, UUID eventId) {
        GlycemicEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        if (!event.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"); // don't leak existence
        }
        if (event.getState() != State.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event already resolved");
        }
        return event;
    }

    /**
     * Saves the answer, its history row and the mirrored children, flushing so that a concurrent scan
     * write (optimistic lock) surfaces here as 409 rather than as a 500 at commit.
     */
    private GlycemicEvent writeAnswer(GlycemicEvent event, Kind kind, Category before) {
        try {
            GlycemicEvent saved = eventRepository.save(event);
            historyRecorder.record(saved, kind, before, Actor.USER, null);
            mirrorChildren(saved);
            eventRepository.flush();
            return saved;
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event changed meanwhile - reload and try again");
        }
    }

    /** {@code now} is the user's wall time, like every other event timestamp. */
    private void resolve(GlycemicEvent e, State state, LocalDateTime now) {
        e.setState(state);
        e.setPhase(Phase.FINAL);
        e.setResolvesAt(null);
        e.setResolvedAt(now);
        e.setUpdatedAt(now);
    }

    private void mirrorChildren(GlycemicEvent parent) {
        for (GlycemicEvent child : eventRepository.findByLinkedEventId(parent.getId())) {
            child.setState(parent.getState());
            child.setCategory(parent.getCategory());
            child.setUserLabel(parent.getUserLabel());
            child.setResolvedAt(parent.getResolvedAt());
            child.setUpdatedAt(parent.getUpdatedAt());
            eventRepository.save(child);
        }
    }

    private void backfill(UUID userId, LocalDateTime at, double carbs, double insulin) {
        Note note = new Note();
        note.setUserId(userId);
        note.setTimestamp(at);
        note.setCarbs(carbs);
        note.setInsulin(insulin);
        note.setMeal("Unlogged (backfilled)");
        note.setType(Note.TYPE_NORMAL);
        note.setCreatedAt(LocalDateTime.now());
        note.setUpdatedAt(LocalDateTime.now());
        Note saved = noteRepository.save(note);
        // Backfills are timed at the event onset, so normally backdated (no snapshot); the
        // freezer decides, after commit, never blocking the confirm.
        if (saved != null) forecastSnapshotTrigger.noteSaved(userId, saved.getId());
        // A backfilled dose was missing from every PLANNED plan's IOB: supersede them (stacking guard).
        if (saved != null && che.glucosemonitorbe.domain.RapidInsulinLogged.isRapidDose(saved)) {
            eventPublisher.publishEvent(new che.glucosemonitorbe.domain.RapidInsulinLogged(userId, saved.getId(), null));
        }
    }

    private static Category parseCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Category.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw unprocessable("Unknown category: " + raw);
        }
    }

    private static double validateAmount(Double v, double max, String name) {
        double x = v == null ? 0.0 : v;
        if (x < 0 || x > max) throw unprocessable("Invalid backfill " + name + ": " + v);
        return x;
    }

    private ZoneId userZone(UUID userId) {
        UserSettingsDTO s = userSettingsService.getUserSettings(userId);
        return s == null ? ZoneOffset.UTC : UserZones.resolve(s.getTimezone(), s.getUtcOffsetMinutes());
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
