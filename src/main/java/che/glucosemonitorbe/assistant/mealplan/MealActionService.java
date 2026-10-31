package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.assistant.chat.ChatHistoryService;
import che.glucosemonitorbe.assistant.chat.ChatMessage;
import che.glucosemonitorbe.assistant.chat.ChatMessageRepository;
import che.glucosemonitorbe.assistant.quicklog.AssistantNoteWriter;
import che.glucosemonitorbe.assistant.quicklog.QuickLogCommand;
import che.glucosemonitorbe.assistant.quicklog.QuickLogRejectedException;
import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.exception.ResourceNotFoundException;
import che.glucosemonitorbe.service.NotesService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The meal card's actions, as a state machine on the assistant plan message's payload:
 * {@code PLANNED -> TOOK -> EATEN | SKIPPED} (and {@code PLANNED -> SKIPPED}). Took saves only the
 * dose note; Eating now saves the meal note at the tap moment. No action ever writes a note dated
 * after {@code now}, so a skipped or late meal never leaves a phantom meal in COB. Event proposals:
 * {@code PROPOSED -> CONFIRMED}. Each action row-locks the message, so a double tap acts once
 * (the second gets 409).
 */
@Service
@RequiredArgsConstructor
public class MealActionService {

    public static final String INTENT_MEAL_PLAN = "meal_plan";
    public static final String INTENT_EVENT_PROPOSAL = "event_proposal";

    public static final String PLANNED = "PLANNED";
    public static final String TOOK = "TOOK";
    public static final String EATEN = "EATEN";
    public static final String SKIPPED = "SKIPPED";
    public static final String PROPOSED = "PROPOSED";
    public static final String CONFIRMED = "CONFIRMED";

    public static final String DOSE_SOURCE_PLAN = "PLAN";
    public static final String DOSE_SOURCE_USER_ENTERED = "USER_ENTERED";

    /** A plan's dose may be prefilled on the card only until plannedAt + this. */
    public static final Duration DOSE_VALIDITY = Duration.ofMinutes(15);
    /** The card turns amber when the meal is not logged within pause + this. */
    static final Duration OVERDUE_GRACE = Duration.ofMinutes(45);
    static final int MAX_PAUSE_MINUTES = 60;
    static final double MAX_CARBS_G = 300.0;
    static final double MAX_FAT_PROTEIN_G = 300.0;
    static final double MAX_FIBER_G = 100.0;

    private final ChatMessageRepository repository;
    private final ChatHistoryService history;
    private final AssistantNoteWriter noteWriter;
    private final NotesService notesService;
    private final ObjectMapper objectMapper;

    /** Outcome of a card action: the new state, the note it saved, and the message's full payload. */
    public record ActionResult(UUID messageId, String state, UUID noteId, JsonNode payload) {}

    /** Took it: saves the dose note at {@code now} and starts the pause; the planned meal stays on the message. */
    @Transactional
    public ActionResult took(UUID userId, UUID messageId, double doseUnits, int pauseMinutes, LocalDateTime now) {
        if (!Double.isFinite(doseUnits) || doseUnits <= 0) {
            throw badRequest("doseUnits must be greater than 0");
        }
        if (pauseMinutes < 0 || pauseMinutes > MAX_PAUSE_MINUTES) {
            throw badRequest("pauseMinutes must be between 0 and " + MAX_PAUSE_MINUTES);
        }
        ChatMessage m = locked(userId, messageId, INTENT_MEAL_PLAN);
        ObjectNode p = history.readPayload(m);
        requireState(p, Set.of(PLANNED));

        UUID noteId;
        try {
            noteId = noteWriter.write(userId, new QuickLogCommand(QuickLogCommand.Kind.RAPID, doseUnits, null, now),
                    m.getId()).noteIds().get(0);
        } catch (QuickLogRejectedException e) {
            throw badRequest(e.getMessage()); // e.g. above the user's max bolus
        }
        JsonNode advised = p.path("plan").path("doseUnits");
        p.put("state", TOOK);
        p.put("tookAt", iso(now));
        p.put("doseUnits", doseUnits);
        if (advised.isNumber()) p.put("advisedDoseUnits", advised.asDouble());
        else p.putNull("advisedDoseUnits");
        p.put("doseSource", advisedDoseTaken(p, doseUnits, now) ? DOSE_SOURCE_PLAN : DOSE_SOURCE_USER_ENTERED);
        p.put("pauseMinutes", pauseMinutes);
        p.put("eatAt", iso(now.plusMinutes(pauseMinutes)));
        p.put("overdueAt", iso(now.plusMinutes(pauseMinutes).plus(OVERDUE_GRACE)));
        p.put("doseNoteId", noteId.toString());
        return save(m, p, noteId);
    }

    /** Eating now with the planned macros. */
    @Transactional
    public ActionResult eating(UUID userId, UUID messageId, MealMacros macros, LocalDateTime now) {
        return macros == null
                ? eating(userId, messageId, null, null, null, null, null, now)
                : eating(userId, messageId, macros.carbs(), macros.fat(), macros.protein(), macros.fiber(),
                        macros.gi(), now);
    }

    /**
     * Eating now: saves the meal note at {@code now}. Each null macro is taken from the planned meal,
     * so the card may send only what the user edited.
     */
    @Transactional
    public ActionResult eating(UUID userId, UUID messageId, Double carbs, Double fat, Double protein, Double fiber,
                               Double gi, LocalDateTime now) {
        ChatMessage m = locked(userId, messageId, INTENT_MEAL_PLAN);
        ObjectNode p = history.readPayload(m);
        requireState(p, Set.of(TOOK));
        JsonNode planned = p.path("meal");
        MealMacros meal = new MealMacros(
                pick(carbs, planned, "carbs"), pick(fat, planned, "fat"), pick(protein, planned, "protein"),
                pick(fiber, planned, "fiber"), gi != null ? gi : nullable(planned, "gi"));
        validate(meal);

        CreateNoteRequest req = new CreateNoteRequest();
        req.setTimestamp(now);
        req.setCarbs(meal.carbs());
        req.setInsulin(0.0);
        req.setMeal("Meal");
        req.setType(Note.TYPE_NORMAL);
        req.setComment(truncate(p.path("description").asText(null), 500));
        req.setChatMessageId(m.getId());
        // Same shape as the plan's prospective meal, so the logged meal forecasts like the planned one.
        req.setNutritionProfile(json(NutritionSnapshot.builder()
                .totalCarbs(meal.carbs()).fat(meal.fat()).protein(meal.protein()).fiber(meal.fiber())
                .estimatedGi(meal.gi()).build()));
        UUID noteId = notesService.createNote(userId, req).getId();

        p.put("state", EATEN);
        p.put("eatenAt", iso(now));
        p.put("mealNoteId", noteId.toString());
        p.set("eatenMeal", objectMapper.valueToTree(meal));
        return save(m, p, noteId);
    }

    /** Not eating: ends the plan. After Took the dose note stays (the insulin was injected). */
    @Transactional
    public ActionResult skip(UUID userId, UUID messageId, LocalDateTime now) {
        ChatMessage m = locked(userId, messageId, INTENT_MEAL_PLAN);
        ObjectNode p = history.readPayload(m);
        requireState(p, Set.of(PLANNED, TOOK));
        p.put("state", SKIPPED);
        p.put("skippedAt", iso(now));
        return save(m, p, null);
    }

    /** Confirm a proposed event: saves a note at {@code now} with the proposal's context tags. */
    @Transactional
    public ActionResult confirmEvent(UUID userId, UUID messageId, LocalDateTime now) {
        ChatMessage m = locked(userId, messageId, INTENT_EVENT_PROPOSAL);
        ObjectNode p = history.readPayload(m);
        requireState(p, Set.of(PROPOSED));
        CreateNoteRequest req = new CreateNoteRequest();
        req.setTimestamp(now);
        req.setCarbs(0.0);
        req.setInsulin(0.0);
        req.setMeal("Event");
        req.setType(Note.TYPE_NORMAL);
        req.setComment(truncate(p.path("comment").asText(null), 500));
        req.setContextTags(p.path("tags").isArray() ? p.get("tags").toString() : "[]");
        req.setChatMessageId(m.getId());
        UUID noteId = notesService.createNote(userId, req).getId();
        p.put("state", CONFIRMED);
        p.put("confirmedAt", iso(now));
        p.put("noteId", noteId.toString());
        return save(m, p, noteId);
    }

    /** The dose was taken but the meal is not logged by tookAt + pause + 45 min. */
    public boolean overdue(ChatMessage m, LocalDateTime now) {
        ObjectNode p = history.readPayload(m);
        if (!TOOK.equals(p.path("state").asText()) || !p.hasNonNull("tookAt")) return false;
        LocalDateTime tookAt = LocalDateTime.parse(p.get("tookAt").asText());
        return now.isAfter(tookAt.plusMinutes(p.path("pauseMinutes").asInt(0)).plus(OVERDUE_GRACE));
    }

    /** Local wall time as ISO-8601 with seconds always present, e.g. {@code 2026-10-31T12:00:00}. */
    public static String iso(LocalDateTime t) {
        return t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    // -- helpers -------------------------------------------------------------------------------

    /**
     * The injected dose is the plan's advice only when the plan had a dose (fresh CGM, no refusal),
     * the Took is not past {@code doseValidUntil}, and the entered dose equals the advised one.
     */
    private static boolean advisedDoseTaken(ObjectNode p, double doseUnits, LocalDateTime now) {
        JsonNode plan = p.path("plan");
        JsonNode advised = plan.path("doseUnits");
        if (!advised.isNumber() || plan.path("stale").asBoolean(false) || plan.hasNonNull("refusalCode")) {
            return false;
        }
        if (!p.hasNonNull("doseValidUntil") || now.isAfter(LocalDateTime.parse(p.get("doseValidUntil").asText()))) {
            return false;
        }
        return Math.abs(advised.asDouble() - doseUnits) < 1e-9;
    }

    private ChatMessage locked(UUID userId, UUID messageId, String intent) {
        ChatMessage m = repository.findForUpdate(messageId)
                .filter(x -> userId.equals(x.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Chat message not found: " + messageId));
        if (m.getRole() != ChatMessage.Role.ASSISTANT || !intent.equals(m.getIntent())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This message does not support that action");
        }
        return m;
    }

    private static void requireState(ObjectNode p, Set<String> allowed) {
        String state = p.path("state").asText("");
        if (!allowed.contains(state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not allowed in state " + state);
        }
    }

    private ActionResult save(ChatMessage m, ObjectNode p, UUID noteId) {
        m.setPayload(p.toString());
        if (noteId != null) {
            List<UUID> ids = new ArrayList<>(history.readNoteIds(m));
            ids.add(noteId);
            m.setNoteIds(history.writeNoteIds(ids));
        }
        history.save(m);
        return new ActionResult(m.getId(), p.path("state").asText(), noteId, p);
    }

    private static void validate(MealMacros meal) {
        check(meal.carbs(), MAX_CARBS_G, "carbs");
        check(meal.fat(), MAX_FAT_PROTEIN_G, "fat");
        check(meal.protein(), MAX_FAT_PROTEIN_G, "protein");
        check(meal.fiber(), MAX_FIBER_G, "fiber");
        if (meal.gi() != null && (!Double.isFinite(meal.gi()) || meal.gi() < 0 || meal.gi() > 100)) {
            throw badRequest("gi must be between 0 and 100");
        }
        if (meal.carbs() + meal.fat() + meal.protein() <= 0) {
            throw badRequest("The meal needs carbs, fat or protein");
        }
    }

    private static void check(double v, double max, String name) {
        if (!Double.isFinite(v) || v < 0 || v > max) {
            throw badRequest(name + " must be between 0 and " + (int) max + " g");
        }
    }

    private static double pick(Double edited, JsonNode planned, String field) {
        if (edited != null) return edited;
        return planned.path(field).asDouble(0.0);
    }

    private static Double nullable(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asDouble() : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
