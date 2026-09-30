package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.repository.GlycemicEventHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Appends one history row. Call after the event is saved (it needs the id), in the same transaction. */
@Component
@RequiredArgsConstructor
public class GlycemicEventHistoryRecorder {

    private final GlycemicEventHistoryRepository historyRepository;

    public void record(GlycemicEvent e, Kind kind, Category before, Actor actor, String reason) {
        historyRepository.save(GlycemicEventHistory.builder()
                .eventId(e.getId()).userId(e.getUserId()).kind(kind)
                .categoryBefore(before).categoryAfter(e.getCategory())
                .confidence(e.getConfidence()).actor(actor).reason(reason)
                .build());
    }
}
