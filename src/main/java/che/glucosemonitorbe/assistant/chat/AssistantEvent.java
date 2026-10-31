package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.assistant.common.ContextTag;
import che.glucosemonitorbe.assistant.mealplan.MealPlan;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;
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

    /** Similar past episodes with outcomes (later task). */
    record Episodes(List<Map<String, Object>> episodes) implements AssistantEvent {}

    /** A warning banner, e.g. the 20% history-dose banner (later task). */
    record Banner(String level, String text) implements AssistantEvent {}

    /** A chunk of reply text. */
    record Token(String text) implements AssistantEvent {}

    /** Ids of clinical passages cited by the reply (later task). */
    record Citations(List<String> ids) implements AssistantEvent {}

    /** End of the reply. Ids of the persisted user and assistant chat messages. */
    record Done(UUID userMessageId, UUID assistantMessageId, String intent) implements AssistantEvent {}

    /** The reply failed; the stream ends after this event. */
    record Error(String message) implements AssistantEvent {}
}
