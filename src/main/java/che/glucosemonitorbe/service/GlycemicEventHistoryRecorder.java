package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.repository.GlycemicEventHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Appends one history row, stamped with the user's wall time (like every other event timestamp). Call
 * after the event is saved (it needs the id), in the same transaction.
 */
@Component
@RequiredArgsConstructor
public class GlycemicEventHistoryRecorder {

    private final GlycemicEventHistoryRepository historyRepository;
    private final UserSettingsService userSettingsService;

    public void record(GlycemicEvent e, Kind kind, Category before, Actor actor, String reason) {
        historyRepository.save(GlycemicEventHistory.builder()
                .eventId(e.getId()).userId(e.getUserId()).at(LocalDateTime.now(userZone(e))).kind(kind)
                .categoryBefore(before).categoryAfter(e.getCategory())
                .confidence(e.getConfidence()).actor(actor).reason(reason)
                .build());
    }

    private ZoneId userZone(GlycemicEvent e) {
        UserSettingsDTO s = userSettingsService.getUserSettings(e.getUserId());
        return s == null ? ZoneOffset.UTC : UserZones.resolve(s.getTimezone(), s.getUtcOffsetMinutes());
    }
}
