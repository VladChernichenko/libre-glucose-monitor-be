package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.assistant.common.ContextTag;
import che.glucosemonitorbe.assistant.mealplan.MealActionService;
import che.glucosemonitorbe.assistant.mealplan.MealMacros;
import che.glucosemonitorbe.assistant.mealplan.MealNutritionResolver;
import che.glucosemonitorbe.assistant.mealplan.MealPlan;
import che.glucosemonitorbe.assistant.mealplan.MealPlanService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The meal-prep route (spec §6.4) and the free-text event proposal (§6.1 path 3):
 * nutrition -> {@link MealPlanService} -> {@code plan} event -> {@code episodes} event (top similar meals)
 * -> explanation (facts payload, number-guarded, sent as one token); or a
 * {@code proposal} event whose note is saved only on Confirm. The plan's state lives on the
 * assistant message's payload and is advanced by {@link MealActionService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MealPrepFlow {

    public static final String INTENT_MEAL_PREP = "meal_prep";
    public static final String INTENT_MEAL_PREP_FAILED = "meal_prep_failed";
    public static final String INTENT_EVENT = "event";
    static final int MAX_COMMENT_LENGTH = 500;

    private final ChatHistoryService history;
    private final MealNutritionResolver nutrition;
    private final MealPlanService planService;
    private final MealActionService mealActions;
    private final ObjectMapper objectMapper;
    private final FactsService factsService;
    private final EpisodeRetrieval episodeRetrieval;
    private final GuardedReplyService guardedReply;

    public void mealPrep(UUID userId, ChatMessage userMessage, IncomingMessage msg,
                         List<IntentClassifier.FoodItem> items, String language, Consumer<AssistantEvent> sink) {
        long start = System.currentTimeMillis();
        boolean ru = "ru".equals(language);
        userMessage.setIntent(INTENT_MEAL_PREP);
        history.save(userMessage);

        Optional<NutritionSnapshot> n = nutrition.resolve(msg.photoKey(), items);
        if (n.isEmpty()) {
            String text = ru
                    ? "Не удалось распознать блюдо. Напишите продукты с весом, например «хлеб 40 г»."
                    : "I couldn't recognise the meal. Send the foods with weights, e.g. \"bread 40 g\".";
            ObjectNode payload = history.newPayload().put("userMessageId", userMessage.getId().toString());
            ChatMessage reply = history.appendAssistant(userMessage, text, INTENT_MEAL_PREP_FAILED, payload, null,
                    System.currentTimeMillis() - start);
            sink.accept(new AssistantEvent.Token(text));
            sink.accept(new AssistantEvent.Done(userMessage.getId(), reply.getId(), INTENT_MEAL_PREP_FAILED));
            return;
        }

        MealPlan plan = planService.plan(userId, n.get(), msg.clientNow());
        String description = description(msg, items);
        ObjectNode payload = history.newPayload()
                .put("userMessageId", userMessage.getId().toString())
                .put("language", language)
                .put("state", MealActionService.PLANNED)
                .put("description", description)
                .put("plannedAt", MealActionService.iso(msg.clientNow()))
                .put("doseValidUntil", MealActionService.iso(msg.clientNow().plus(MealActionService.DOSE_VALIDITY)));
        MealMacros macros = new MealMacros(plan.carbs().mid(), plan.fat().mid(),
                plan.protein().mid(), plan.fiber().mid(), plan.gi());
        payload.set("meal", objectMapper.valueToTree(macros));
        ArrayNode itemsNode = payload.putArray("items");
        for (IntentClassifier.FoodItem i : items) {
            ObjectNode item = itemsNode.addObject().put("name", i.name());
            if (i.grams() != null) item.put("grams", i.grams());
        }
        payload.set("plan", objectMapper.valueToTree(plan));
        ChatMessage reply = history.appendAssistant(userMessage, "", MealActionService.INTENT_MEAL_PLAN, payload,
                null, System.currentTimeMillis() - start);
        // Only the newest plan can be acted on; older PLANNED cards turn SUPERSEDED (TOOK ones never).
        mealActions.supersedeOlderPlans(userId, reply.getId(), msg.clientNow());
        sink.accept(new AssistantEvent.Plan(reply.getId(), plan, payload.get("plannedAt").asText(),
                payload.get("doseValidUntil").asText()));

        FactsPayload.State state = null;
        List<FactsPayload.EpisodeFacts> episodes = List.of();
        try {
            state = factsService.state(userId, msg.clientNow(), msg.zone());
            episodes = episodeRetrieval.forPlan(userId, description, macros, msg.clientNow(), state.glucose(),
                    state.iob() == null ? 0.0 : state.iob());
        } catch (RuntimeException e) {
            // Episodes and state are context for the explanation; their absence must not fail the card.
            log.warn("Meal-prep facts incomplete for userId={}: {}", userId, e.toString());
        }
        if (!episodes.isEmpty()) sink.accept(new AssistantEvent.Episodes(episodes));

        StringBuilder seen = new StringBuilder();
        String modelId = null;
        try {
            FactsPayload facts = FactsPayload.builder()
                    .language(language)
                    .now(FactsService.wall(msg.clientNow()))
                    .state(state)
                    .plan(FactsService.plan(plan, description))
                    .episodes(episodes)
                    .build();
            GuardedReplyService.Result r = guardedReply.reply(userId, facts,
                    offending -> AssistantPrompts.plan(facts, offending),
                    () -> AssistantPrompts.planTemplate(facts), language);
            modelId = r.modelId();
            sink.accept(new AssistantEvent.Token(r.text()));
            seen.append(r.text());
        } catch (RuntimeException e) {
            // The card already went out; a missing explanation must not fail it.
            log.warn("Meal-prep explanation failed for userId={}: {}", userId, e.toString());
        }
        history.updateReplyText(reply.getId(), seen.toString(), modelId, System.currentTimeMillis() - start);
        sink.accept(new AssistantEvent.Done(userMessage.getId(), reply.getId(), MealActionService.INTENT_MEAL_PLAN));
    }

    public void proposeEvent(ChatMessage userMessage, String text, List<ContextTag> tags, String language,
                             Consumer<AssistantEvent> sink) {
        long start = System.currentTimeMillis();
        String comment = text.strip();
        if (comment.length() > MAX_COMMENT_LENGTH) comment = comment.substring(0, MAX_COMMENT_LENGTH);
        userMessage.setIntent(INTENT_EVENT);
        history.save(userMessage);

        ObjectNode payload = history.newPayload()
                .put("userMessageId", userMessage.getId().toString())
                .put("language", language)
                .put("state", MealActionService.PROPOSED)
                .put("comment", comment);
        ArrayNode tagsNode = payload.putArray("tags");
        tags.forEach(t -> tagsNode.add(t.name()));
        String names = tags.stream().map(t -> t.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .collect(Collectors.joining(", "));
        String reply = "ru".equals(language)
                ? "Записать заметку (" + names + ")? Нажмите «Подтвердить»."
                : "Save a note (" + names + ")? Tap Confirm to log it.";
        ChatMessage saved = history.appendAssistant(userMessage, reply, MealActionService.INTENT_EVENT_PROPOSAL,
                payload, null, System.currentTimeMillis() - start);
        sink.accept(new AssistantEvent.Proposal(saved.getId(), tags, comment));
        sink.accept(new AssistantEvent.Done(userMessage.getId(), saved.getId(), MealActionService.INTENT_EVENT_PROPOSAL));
    }

    private static String description(IncomingMessage msg, List<IntentClassifier.FoodItem> items) {
        if (items != null && !items.isEmpty()) {
            return items.stream()
                    .map(i -> i.grams() == null ? i.name() : i.name() + " " + fmt(i.grams()) + " g")
                    .collect(Collectors.joining(", "));
        }
        return msg.hasPhoto() ? "meal photo" : "meal";
    }

    private static String fmt(Double v) {
        if (v == null || !Double.isFinite(v)) return "?";
        double r = Math.round(v * 10.0) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
