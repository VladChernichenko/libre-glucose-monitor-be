package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.GlycemicEventDTO;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * User-facing operations on glycemic events: list, confirm (optionally backfilling the real amount as
 * a note) and dismiss. Detection lives in {@link GlycemicEventScanService}.
 */
@Service
@RequiredArgsConstructor
public class GlycemicEventService {

    private final GlycemicEventRepository eventRepository;
    private final NoteRepository noteRepository;
    private final GlycemicEventHistoryRecorder historyRecorder;

    @Value("${app.glycemic-events.backfill-max-carbs:300}")
    private double maxBackfillCarbs;
    @Value("${app.glycemic-events.backfill-max-insulin:50}")
    private double maxBackfillInsulin;

    @Transactional(readOnly = true)
    public List<GlycemicEventDTO> list(UUID userId, State stateFilter) {
        List<GlycemicEvent> found = stateFilter == null
                ? eventRepository.findByUserIdOrderByDetectedAtDesc(userId)
                : eventRepository.findByUserIdAndStateOrderByDetectedAtDesc(userId, stateFilter);
        return found.stream().map(GlycemicEventDTO::from).toList();
    }

    @Transactional
    public GlycemicEventDTO confirm(UUID userId, UUID eventId, Double carbs, Double insulin) {
        GlycemicEvent event = requireOpen(userId, eventId);
        boolean backfill = (carbs != null && carbs > 0) || (insulin != null && insulin > 0);
        if (backfill) {
            double c = validateAmount(carbs, maxBackfillCarbs, "carbs");
            double u = validateAmount(insulin, maxBackfillInsulin, "insulin");
            Note note = new Note();
            note.setUserId(userId);
            note.setTimestamp(event.getWindowStart());
            note.setCarbs(c);
            note.setInsulin(u);
            note.setMeal("Unlogged (backfilled)");
            note.setType(Note.TYPE_NORMAL);
            note.setCreatedAt(LocalDateTime.now());
            note.setUpdatedAt(LocalDateTime.now());
            noteRepository.save(note);
        }
        event.setState(State.CONFIRMED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        GlycemicEvent saved = eventRepository.save(event);
        historyRecorder.record(saved, Kind.CONFIRMED, saved.getCategory(), Actor.USER, null);
        return GlycemicEventDTO.from(saved);
    }

    @Transactional
    public GlycemicEventDTO dismiss(UUID userId, UUID eventId) {
        GlycemicEvent event = requireOpen(userId, eventId);
        event.setState(State.DISMISSED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        GlycemicEvent saved = eventRepository.save(event);
        historyRecorder.record(saved, Kind.DISMISSED, saved.getCategory(), Actor.USER, null);
        return GlycemicEventDTO.from(saved);
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

    double validateAmount(Double v, double max, String name) {
        double x = v == null ? 0.0 : v;
        if (x < 0 || x > max) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid backfill " + name + ": " + v);
        }
        return x;
    }
}
