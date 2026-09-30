package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.glycemic.EventLabelLearner.LabelledExample;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The learner's per-user training set: answered, top-level events with a feature vector. Cached 60 s
 * (same pattern as the digital-twin cache) and invalidated whenever the user answers an event.
 */
@Component
public class GlycemicLearnerTrainingCache {

    private static final Duration TTL = Duration.ofSeconds(60);

    private record Entry(List<GlycemicEvent> events, Instant loadedAt) {}

    private final GlycemicEventRepository eventRepository;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public GlycemicLearnerTrainingCache(GlycemicEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public List<LabelledExample> examples(UUID userId, Direction direction) {
        Entry entry = cache.compute(userId, (id, old) -> old != null && old.loadedAt().plus(TTL).isAfter(Instant.now())
                ? old
                : new Entry(eventRepository.findByUserIdAndStateIn(id, List.of(State.CONFIRMED, State.DISMISSED)), Instant.now()));
        return entry.events().stream()
                .filter(e -> e.getFeatures() != null && e.getLinkedEventId() == null && e.getDirection() == direction)
                .map(e -> new LabelledExample(e.getState() == State.DISMISSED ? null
                        : e.getUserLabel() != null ? e.getUserLabel() : e.getDetectorCategory(), e.getFeatures()))
                .toList();
    }

    public void invalidate(UUID userId) { cache.remove(userId); }
}
