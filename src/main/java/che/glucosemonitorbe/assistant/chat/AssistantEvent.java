package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.assistant.common.ContextTag;
import che.glucosemonitorbe.assistant.mealplan.HistoryBanner;
import che.glucosemonitorbe.assistant.mealplan.MealPlan;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.UUID;

/**
 * One NDJSON line of an assistant reply stream. Serialised with a {@code "type"} discriminator:
 * {@code logged, plan, proposal, episodes, banner, token, citations, done, error}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AssistantEvent.Logged.class, name = "logged"),
        @JsonSubTypes.Type(value = AssistantEvent.Plan.class, name = "plan"),
        @JsonSubTypes.Type(value = AssistantEvent.Proposal.class, name = "proposal"),
        @JsonSubTypes.Type(value = AssistantEvent.Episodes.class, name = "episodes"),
        @JsonSubTypes.Type(value = AssistantEvent.Banner.class, name = "banner"),
        @JsonSubTypes.Type(value = AssistantEvent.Token.class, name = "token"),
        @JsonSubTypes.Type(value = AssistantEvent.Citations.class, name = "citations"),
        @JsonSubTypes.Type(value = AssistantEvent.Done.class, name = "done"),
        @JsonSubTypes.Type(value = AssistantEvent.Error.class, name = "error")
})
public sealed interface AssistantEvent permits AssistantEvent.Logged, AssistantEvent.Plan, AssistantEvent.Proposal,
        AssistantEvent.Episodes, AssistantEvent.Banner, AssistantEvent.Token, AssistantEvent.Citations,
        AssistantEvent.Done, AssistantEvent.Error {

    /** The wire discriminator for this event. */
    @JsonIgnore
    default String type() {
        return switch (this) {
            case Logged l -> "logged";
            case Plan p -> "plan";
            case Proposal p -> "proposal";
            case Episodes e -> "episodes";
            case Banner b -> "banner";
            case Token t -> "token";
            case Citations c -> "citations";
            case Done d -> "done";
            case Error e -> "error";
        };
    }

    /** Quick-log notes were written. {@code forecastMin} is the lowest 4 h forecast value, or null. */
    record Logged(List<UUID> noteIds, String summary, double iob, Double forecastMin) implements AssistantEvent {}

    /**
     * Structured meal-prep card. {@code messageId} is the assistant chat message that holds the
     * plan's state; the card's Took it / Eating now / Not eating actions post to it. Times are the
     * user's wall clock (ISO, no offset). After {@code doseValidUntil} the card must not prefill or
     * show the planned dose (re-plan or manual entry).
     */
    record Plan(UUID messageId, MealPlan plan, String plannedAt, String doseValidUntil) implements AssistantEvent {}

    /**
     * A free-text event proposed as a note with context tags; saved only when the user confirms
     * ({@code POST /api/assistant/events/{messageId}/confirm}).
     */
    record Proposal(UUID messageId, List<ContextTag> tags, String comment) implements AssistantEvent {}

    /**
     * Past episodes with outcomes shown as cards (at most 5): after {@code plan} for a meal plan, before the
     * reply tokens for a question. Null fields are omitted on the wire.
     */
    record Episodes(List<FactsPayload.EpisodeFacts> episodes) implements AssistantEvent {}

    /**
     * The 20% history-dose banner (spec 6.6), right after {@code plan} when the plan has a history dose.
     * {@code messageId} is the plan's message; the same banner is persisted on its payload as
     * {@code banner}. Choosing the history dose goes through {@code POST /api/assistant/plans/{messageId}/recheck}.
     */
    record Banner(UUID messageId, HistoryBanner banner) implements AssistantEvent {}

    /** A chunk of reply text. */
    record Token(String text) implements AssistantEvent {}

    /**
     * Clinical passages behind a question reply, shown as tappable chips under it (spec §6.9). Sent after
     * {@code episodes} and before {@code token}. {@code ref} is the number the reply text cites as
     * {@code [ref]}; {@code id} is the clinical_knowledge_chunk id; {@code year}, {@code url}, {@code source}
     * and {@code citation} may be absent (the GENERAL disclaimer has no URL). Null fields are omitted.
     */
    record Citations(List<Citation> citations) implements AssistantEvent {}

    /** One citation chip. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Citation(Integer ref, String id, String title, String source, Integer year, String url,
                    String citation) {}

    /** End of the reply. Ids of the persisted user and assistant chat messages. */
    record Done(UUID userMessageId, UUID assistantMessageId, String intent) implements AssistantEvent {}

    /** The reply failed; the stream ends after this event. */
    record Error(String message) implements AssistantEvent {}
}
