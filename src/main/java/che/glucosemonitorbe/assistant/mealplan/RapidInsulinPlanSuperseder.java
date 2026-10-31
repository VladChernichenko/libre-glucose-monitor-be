package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.domain.RapidInsulinLogged;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Stacking guard: any rapid dose logged outside a plan's own Took (quick log, Notes tab, a glycemic
 * prompt backfill) supersedes the user's PLANNED plans, whose dose was priced without it. Runs in the
 * note writer's transaction, so a failure rolls the note back rather than leaving a stackable plan.
 */
@Component
@RequiredArgsConstructor
public class RapidInsulinPlanSuperseder {

    private final MealActionService mealActions;
    private final UserSettingsService settings;

    @EventListener
    public void onRapidInsulin(RapidInsulinLogged e) {
        mealActions.supersedeForRapidInsulin(e.userId(), e.noteId(), e.chatMessageId(),
                LocalDateTime.now(zone(e)));
    }

    private ZoneId zone(RapidInsulinLogged e) {
        UserSettingsDTO s = settings.getUserSettings(e.userId());
        return s == null ? ZoneOffset.UTC : UserZones.resolve(s.getTimezone(), s.getUtcOffsetMinutes());
    }
}
