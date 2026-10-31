package che.glucosemonitorbe.assistant.quicklog;

import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.NotesService;
import che.glucosemonitorbe.service.UserSettingsService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persists a parsed {@link QuickLogCommand} as notes on behalf of the assistant, enforcing the
 * dose and carb bounds, tagging every note with the originating chat message, and supporting undo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantNoteWriter {

    static final double MAX_CARBS_G = 300.0;
    private static final double MAX_BASAL_UNITS = 100.0;

    private final NotesService notesService;
    private final HypoEventService hypoEventService;
    private final UserSettingsService userSettingsService;
    private final InsulinCalculatorService insulinCalculatorService;

    public record WriteResult(List<UUID> noteIds, UUID hypoEventId) {}

    public WriteResult write(UUID userId, QuickLogCommand cmd, UUID chatMessageId) {
        validate(userId, cmd);
        return switch (cmd.kind()) {
            case FAST_SUGAR -> writeFastSugar(userId, cmd, chatMessageId);
            case RAPID -> single(userId, cmd, chatMessageId, Note.TYPE_NORMAL, cmd.units(), 0.0, "Insulin");
            case RAPID_WITH_MEAL -> single(userId, cmd, chatMessageId, Note.TYPE_NORMAL, cmd.units(), cmd.carbs(), "Meal");
            case MEAL -> single(userId, cmd, chatMessageId, Note.TYPE_NORMAL, 0.0, cmd.carbs(), "Meal");
            case LONG_ACTING -> single(userId, cmd, chatMessageId, Note.TYPE_LONG_ACTING, cmd.units(), 0.0, "Long-acting insulin");
        };
    }

    /**
     * Deletes the notes the write created. A rescue note created through a hypo event is deleted
     * like any other; the event is deliberately not reopened (its FK is ON DELETE SET NULL).
     */
    public void undo(UUID userId, WriteResult result) {
        for (UUID noteId : result.noteIds()) {
            notesService.deleteNote(userId, noteId);
        }
    }

    private void validate(UUID userId, QuickLogCommand cmd) {
        boolean needsUnits = switch (cmd.kind()) {
            case RAPID, RAPID_WITH_MEAL, LONG_ACTING -> true;
            default -> false;
        };
        boolean needsCarbs = switch (cmd.kind()) {
            case RAPID_WITH_MEAL, MEAL, FAST_SUGAR -> true;
            default -> false;
        };
        if (needsUnits) {
            Double u = cmd.units();
            if (u == null || !Double.isFinite(u) || u <= 0) {
                throw new QuickLogRejectedException("Insulin dose must be greater than 0");
            }
            if (cmd.kind() == QuickLogCommand.Kind.LONG_ACTING) {
                if (u > MAX_BASAL_UNITS) {
                    throw new QuickLogRejectedException("Long-acting dose above " + MAX_BASAL_UNITS + " U");
                }
            } else {
                UserSettingsDTO s = userSettingsService.getUserSettings(userId);
                double max = insulinCalculatorService.maxBolusUnits(s);
                if (u > max) {
                    throw new QuickLogRejectedException(
                            "Dose " + u + " U is above your maximum bolus of " + max + " U");
                }
            }
        }
        if (needsCarbs) {
            Double c = cmd.carbs();
            if (c == null || !Double.isFinite(c) || c <= 0) {
                throw new QuickLogRejectedException("Carbs must be greater than 0");
            }
            if (c > MAX_CARBS_G) {
                throw new QuickLogRejectedException("Carbs above " + (int) MAX_CARBS_G + " g");
            }
        }
    }

    private WriteResult writeFastSugar(UUID userId, QuickLogCommand cmd, UUID chatMessageId) {
        Optional<HypoEventDTO> open = hypoEventService.findOpenEvent(userId);
        if (open.isPresent()) {
            UUID eventId = open.get().id();
            try {
                HypoEventDTO confirmed = hypoEventService.confirm(userId, eventId, cmd.carbs(), chatMessageId);
                if (confirmed.noteId() != null) {
                    return new WriteResult(List.of(confirmed.noteId()), eventId);
                }
            } catch (ResponseStatusException e) {
                // Event went stale / was resolved between lookup and confirm: log a plain note instead.
                if (e.getStatusCode() != HttpStatus.CONFLICT) {
                    throw e;
                }
            }
        }
        return single(userId, cmd, chatMessageId, Note.TYPE_HYPO_TREATMENT, 0.0, cmd.carbs(), "Hypo treatment");
    }

    private WriteResult single(UUID userId, QuickLogCommand cmd, UUID chatMessageId, String type,
                               double insulin, double carbs, String meal) {
        CreateNoteRequest req = new CreateNoteRequest();
        req.setTimestamp(cmd.at());
        req.setCarbs(carbs);
        req.setInsulin(insulin);
        req.setMeal(meal);
        req.setType(type);
        req.setChatMessageId(chatMessageId);
        UUID id = notesService.createNote(userId, req).getId();
        return new WriteResult(List.of(id), null);
    }
}
